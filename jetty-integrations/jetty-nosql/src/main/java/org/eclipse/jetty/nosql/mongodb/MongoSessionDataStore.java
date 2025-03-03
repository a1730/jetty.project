//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.nosql.mongodb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.mongodb.MongoException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Binary;
import org.eclipse.jetty.nosql.NoSqlSessionDataStore;
import org.eclipse.jetty.session.SessionContext;
import org.eclipse.jetty.session.SessionData;
import org.eclipse.jetty.session.UnreadableSessionDataException;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MongoSessionDataStore
 *
 * The document model is an outer object that contains the elements:
 * <ul>
 * <li>"id"      : session_id </li>
 * <li>"created" : create_time </li>
 * <li>"accessed": last_access_time </li>
 * <li>"maxIdle" : max_idle_time setting as session was created </li>
 * <li>"expiry"  : time at which session should expire </li>
 * <li>"valid"   : session_valid </li>
 * <li>"context" : a nested object containing 1 nested object per context for which the session id is in use
 * </ul>
 * Each of the nested objects inside the "context" element contains:
 * <ul>
 * <li>unique_context_name : nested object containing name:value pairs of the session attributes for that context</li>
 * <li>unique_context_name: vhost:contextpath, where no vhosts="0_0_0_0", root context = "", contextpath "/" replaced by "_"
 * </ul>
 * <p>
 * One of the name:value attribute pairs will always be the special attribute "__metadata__". The value
 * is an object representing a version counter which is incremented every time the attributes change.
 * </p>
 * <p>
 * For example:
 * <pre>
 * { "_id"       : ObjectId("52845534a40b66410f228f23"),
 *    "accessed" :  NumberLong("1384818548903"),
 *    "maxIdle"  : 1,
 *    "context"  : { "0_0_0_0:_testA" : { "A"            : "A",
 *                                     "__metadata__" : { "version" : NumberLong(2) }
 *                                   },
 *                   "0_0_0_0:_testB" : { "B"            : "B",
 *                                     "__metadata__" : { "version" : NumberLong(1) }
 *                                   }
 *                 },
 *    "created"  : NumberLong("1384818548903"),
 *    "expiry"   : NumberLong("1384818549903"),
 *    "id"       : "w01ijx2vnalgv1sqrpjwuirprp7",
 *    "valid"    : true
 * }
 * </pre>
 * <p>
 * In MongoDB, the nesting level is indicated by "." separators for the key name. Thus to
 * interact with session fields, the key is composed of:
 * <code>"context".unique_context_name.field_name</code>
 * Eg  <code>"context"."0_0_0_0:_testA"."lastSaved"</code>
 */
@ManagedObject
public class MongoSessionDataStore extends NoSqlSessionDataStore
{

    private static final Logger LOG = LoggerFactory.getLogger(MongoSessionDataStore.class);

    /**
     * Special attribute for a session that is context-specific
     */
    public static final String __METADATA = "__metadata__";

    /**
     * Name of nested document field containing 1 sub document per context for which the session id is in use
     */
    public static final String __CONTEXT = "context";

    /**
     * Special attribute per session per context, incremented each time attributes are modified
     */
    public static final String __VERSION = __METADATA + ".version";

    public static final String __LASTSAVED = __METADATA + ".lastSaved";

    public static final String __LASTNODE = __METADATA + ".lastNode";

    /**
     * Last access time of session
     */
    public static final String __ACCESSED = "accessed";

    public static final String __LAST_ACCESSED = "lastAccessed";

    public static final String __ATTRIBUTES = "attributes";

    /**
     * Time this session will expire, based on last access time and maxIdle
     */
    public static final String __EXPIRY = "expiry";

    /**
     * The max idle time of a session (smallest value across all contexts which has a session with the same id)
     */
    public static final String __MAX_IDLE = "maxIdle";

    /**
     * Time of session creation
     */
    public static final String __CREATED = "created";

    /**
     * Whether or not session is valid
     */
    public static final String __VALID = "valid";

    /**
     * Session id
     */
    public static final String __ID = "id";

    /**
     * Utility value of 1 for a session version for this context
     */
    private DBObject _version1;

    private static final Pattern _workerNamePattern = Pattern.compile("[_0-9a-zA-Z]*");

    /**
     * Access to MongoDB
     */
    private MongoCollection<Document> _dbSessions;

    public void setDBCollection(MongoCollection<Document> collection)
    {
        _dbSessions = collection;
    }

    @ManagedAttribute(value = "DBCollection", readonly = true)
    public MongoCollection<Document> getDBCollection()
    {
        return _dbSessions;
    }

    @Override
    protected void doStart() throws Exception
    {
        checkWorkerName();
        super.doStart();
    }

    private void checkWorkerName() throws IllegalStateException
    {
        if (_context == null || StringUtil.isEmpty(_context.getWorkerName()))
            return;

        if (!_workerNamePattern.matcher(_context.getWorkerName()).matches())
            throw new IllegalStateException("Worker name " + _context.getWorkerName() + " does not match pattern " + _workerNamePattern.pattern());
    }

    @Override
    public SessionData doLoad(String id) throws Exception
    {
        Document sessionDocument = _dbSessions.find(Filters.eq(__ID, id)).first();

        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("id={} loaded={}", id, sessionDocument);

            if (sessionDocument == null)
                return null;

            Boolean valid = (Boolean)sessionDocument.get(__VALID);

            if (LOG.isDebugEnabled())
                LOG.debug("id={} valid={}", id, valid);
            if (valid == null || !valid)
                return null;

            Object version = MongoUtils.getNestedValue(sessionDocument, getContextSubfield(__VERSION));
            Long lastSaved = (Long)MongoUtils.getNestedValue(sessionDocument, getContextSubfield(__LASTSAVED));
            String lastNode = (String)MongoUtils.getNestedValue(sessionDocument, getContextSubfield(__LASTNODE));
            Binary binary = ((Binary)MongoUtils.getNestedValue(sessionDocument, getContextSubfield(__ATTRIBUTES)));
            byte[] attributes = binary == null ? null : binary.getData();

            Long created = (Long)sessionDocument.get(__CREATED);
            Long accessed = (Long)sessionDocument.get(__ACCESSED);
            Long lastAccessed = (Long)sessionDocument.get(__LAST_ACCESSED);
            Long maxInactive = (Long)sessionDocument.get(__MAX_IDLE);
            Long expiry = (Long)sessionDocument.get(__EXPIRY);

            NoSqlSessionData data = null;

            // get the session for the context
            Document sessionSubDocumentForContext = (Document)MongoUtils.getNestedValue(sessionDocument, getContextField());

            if (LOG.isDebugEnabled())
                LOG.debug("attrs {}", sessionSubDocumentForContext);

            if (sessionSubDocumentForContext != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Session {} present for context {}", id, _context);

                //only load a session if it exists for this context
                data = (NoSqlSessionData)newSessionData(id, created, accessed, (lastAccessed == null ? accessed : lastAccessed), maxInactive);
                data.setVersion(version);
                data.setExpiry(expiry);
                data.setContextPath(_context.getCanonicalContextPath());
                data.setVhost(_context.getVhost());
                data.setLastSaved(lastSaved);
                data.setLastNode(lastNode);

                if (attributes == null)
                {
                    //legacy attribute storage format: the attributes are all fields in the document
                    Map<String, Object> map = new HashMap<>();
                    for (String name : sessionSubDocumentForContext.keySet())
                    {
                        //skip special metadata attribute which is not one of the actual session attributes
                        if (__METADATA.equals(name))
                            continue;
                        String attr = MongoUtils.decodeName(name);
                        Object value = MongoUtils.decodeValue(sessionSubDocumentForContext.get(name));
                        map.put(attr, value);
                    }
                    data.putAllAttributes(map);
                }
                else
                {
                    //attributes have special serialized format
                    try (ByteArrayInputStream bais = new ByteArrayInputStream(attributes))
                    {
                        deserializeAttributes(data, bais);
                    }
                }
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Session  {} not present for context {}", id, _context);
            }

            return data;
        }
        catch (Exception e)
        {
            throw (new UnreadableSessionDataException(id, _context, e));
        }
    }

    @Override
    public boolean delete(String id) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Remove:session {} for context {}", id, _context);

        /*
         * Check if the session exists and if it does remove the context
         * associated with this session
         */
        Bson filterId = Filters.eq(__ID, id);

        Document sessionDocument = _dbSessions.find(filterId).first();

        if (sessionDocument != null)
        {
            Document c = (Document)MongoUtils.getNestedValue(sessionDocument, __CONTEXT);
            if (c == null)
            {
                //delete whole doc
                _dbSessions.deleteOne(filterId);
                return false;
            }

            Set<String> contexts = c.keySet();
            if (contexts.isEmpty())
            {
                //delete whole doc
                _dbSessions.deleteOne(filterId);
                return false;
            }

            if (contexts.size() == 1 && contexts.iterator().next().equals(getCanonicalContextId()))
            {
                //delete whole doc
                _dbSessions.deleteOne(filterId);
                return true;
            }

            //just remove entry for my context
            BasicDBObject remove = new BasicDBObject();
            BasicDBObject unsets = new BasicDBObject();
            unsets.put(getContextField(), 1);
            remove.put("$unset", unsets);
            _dbSessions.updateOne(filterId, remove);
            return true;
        }
        else
        {
            return false;
        }
    }

    @Override
    public boolean doExists(String id) throws Exception
    {
        Bson projection = Projections.fields(Projections.include(__ID, __VALID, __EXPIRY, __VERSION, getContextField()), Projections.excludeId());
        Bson filterId = Filters.eq(__ID, id);
        Document sessionDocument = _dbSessions.find(filterId).projection(projection).first();

        if (sessionDocument == null)
            return false; //doesn't exist

        Boolean valid = (Boolean)sessionDocument.get(__VALID);
        if (!valid)
            return false; //invalid - nb should not happen

        Long expiry = (Long)sessionDocument.get(__EXPIRY);

        //expired?
        if (expiry != null && expiry > 0 && expiry < System.currentTimeMillis())
            return false; //it's expired

        //does it exist for this context?
        Object version = MongoUtils.getNestedValue(sessionDocument, getContextSubfield(__VERSION));
        return version != null;
    }

    @Override
    public Set<String> doCheckExpired(Set<String> candidates, long time)
    {

        //firstly ask mongo to verify if these candidate ids have expired - all of
        //these candidates will be for our node
        Bson query = Filters.and(
                Filters.in(__ID, candidates),
                Filters.gt(__EXPIRY, 0),
                Filters.lte(__EXPIRY, time));


        FindIterable<Document> verifiedExpiredSessions = _dbSessions.find(query); // , new BasicDBObject(__ID, 1)
        Set<String> expiredSessions =
                StreamSupport.stream(verifiedExpiredSessions.spliterator(), false)
                        .map(document -> document.getString(__ID))
                        .collect(Collectors.toSet());

        //check through sessions that were candidates, but not found as expired.
        //they may no longer be persisted, in which case they are treated as expired.
        for (String c:candidates)
        {
            if (!expiredSessions.contains(c))
            {
                try
                {
                    if (!exists(c))
                        expiredSessions.add(c);
                }
                catch (Exception e)
                {
                    LOG.warn("Problem checking potentially expired session {}", c, e);
                }
            }
        }
        return expiredSessions;
    }

    @Override
    public Set<String> doGetExpired(long timeLimit)
    {
        // now ask mongo to find sessions for this context, last managed by any
        // node, that expired before timeLimit
        Bson query = Filters.and(
            Filters.gt(__EXPIRY, 0),
            Filters.lte(__EXPIRY, timeLimit)
        );

        //TODO we should verify if there is a session for my context, not any context

        FindIterable<Document> documents = _dbSessions.find(query);
        Set<String> expiredSessions = StreamSupport.stream(documents.spliterator(), false)
                .map(document -> document.getString(__ID))
                .collect(Collectors.toSet());
        return expiredSessions;
    }

    @Override
    public void doCleanOrphans(long timeLimit)
    {
        //Delete all session documents where the expiry time (which is always the most
        //up-to-date expiry of all contexts sharing that session id) has already past as
        //at the timeLimit.
        Bson query = Filters.and(
          Filters.gt(__EXPIRY, 0),
          Filters.lte(__EXPIRY, timeLimit)
        );
        _dbSessions.deleteMany(query);
    }

    /**
     * @see org.eclipse.jetty.session.SessionDataStore#initialize(org.eclipse.jetty.session.SessionContext)
     */
    public void initialize(SessionContext context) throws Exception
    {
        if (isStarted())
            throw new IllegalStateException("Context set after SessionDataStore started");
        _context = context;
        ensureIndexes();
    }

    @Override
    public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
    {
        // Form query for upsert
        Bson key = Filters.eq(__ID, id);;
        // Form updates
        BasicDBObject update = new BasicDBObject();
        boolean upsert = false;
        BasicDBObject sets = new BasicDBObject();

        Object version = ((NoSqlSessionData)data).getVersion();

        // New session
        if (lastSaveTime <= 0)
        {
            upsert = true;
            version = 1L;
            sets.put(__CREATED, data.getCreated());
            sets.put(__VALID, true);
            sets.put(getContextSubfield(__VERSION), version);
            sets.put(getContextSubfield(__LASTSAVED), data.getLastSaved());
            sets.put(getContextSubfield(__LASTNODE), data.getLastNode());
            sets.put(__MAX_IDLE, data.getMaxInactiveMs());
            sets.put(__EXPIRY, data.getExpiry());
            ((NoSqlSessionData)data).setVersion(version);
        }
        else
        {
            sets.put(getContextSubfield(__LASTSAVED), data.getLastSaved());
            sets.put(getContextSubfield(__LASTNODE), data.getLastNode());
            version = ((Number)version).longValue() + 1L;
            ((NoSqlSessionData)data).setVersion(version);
            // what is this?? this field is used no where...
            //sets.put("$inc", _version1);
            //if max idle time and/or expiry is smaller for this context, then choose that for the whole session doc
            BasicDBObject fields = new BasicDBObject();
            fields.append(__MAX_IDLE, true);
            fields.append(__EXPIRY, true);
            Document o = _dbSessions.find(key).first();
            if (o != null)
            {
                Long tmpLong = (Long)o.get(__MAX_IDLE);
                long currentMaxIdle = (tmpLong == null ? 0 : tmpLong.longValue());
                tmpLong = (Long)o.get(__EXPIRY);
                long currentExpiry = (tmpLong == null ? 0 : tmpLong.longValue());

                if (currentMaxIdle != data.getMaxInactiveMs())
                    sets.put(__MAX_IDLE, data.getMaxInactiveMs());

                if (currentExpiry != data.getExpiry())
                    sets.put(__EXPIRY, data.getExpiry());
            }
            else
                LOG.warn("Session {} not found, can't update", id);
        }

        sets.put(__ACCESSED, data.getAccessed());
        sets.put(__LAST_ACCESSED, data.getLastAccessed());

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();)
        {
            serializeAttributes(data, baos);
            Binary binary = new Binary(baos.toByteArray());
            sets.put(getContextSubfield(__ATTRIBUTES), binary);
        }

        // Do the upsert
        if (!sets.isEmpty())
            update.put("$set", sets);

        UpdateResult res = _dbSessions.updateOne(key, update, new UpdateOptions().upsert(upsert));
        if (LOG.isDebugEnabled())
            LOG.debug("Save:db.sessions.update( {}, {},{} )", key, update, res);
    }

    protected void ensureIndexes() throws MongoException
    {
        var indexes =
                StreamSupport.stream(_dbSessions.listIndexes().spliterator(), false)
                        .toList();
        var indexesNames = indexes.stream().map(document -> document.getString("name")).toList();
        if (!indexesNames.contains("id_1"))
        {
            String createResult = _dbSessions.createIndex(Indexes.text("id"),
                    new IndexOptions().unique(true).name("id_1").sparse(false));
            LOG.info("create index {}, result: {}", "id_1", createResult);
        }
        if (!indexesNames.contains("id_1_version_1"))
        {
            // Command failed with error 67 (CannotCreateIndex): 'only one text index per collection allowed, found existing text index "id_1"'
            String createResult = _dbSessions.createIndex(
                    Indexes.compoundIndex(Indexes.descending("id"), Indexes.descending("version")),
                    new IndexOptions().unique(false).name("id_1_version_1").sparse(false));
            LOG.info("create index {}, result: {}", "id_1_version_1", createResult);
        }
        if (LOG.isDebugEnabled())
            LOG.debug("Done ensure Mongodb indexes existing");
        //TODO perhaps index on expiry time?
    }

    private String getContextField()
    {
        return __CONTEXT + "." + getCanonicalContextId();
    }

    private String getCanonicalContextId()
    {
        return canonicalizeVHost(_context.getVhost()) + ":" + _context.getCanonicalContextPath();
    }

    private String canonicalizeVHost(String vhost)
    {
        if (vhost == null)
            return "";

        return StringUtil.replace(vhost, '.', '_');
    }

    private String getContextSubfield(String attr)
    {
        return getContextField() + "." + attr;
    }

    @ManagedAttribute(value = "does store serialize sessions", readonly = true)
    @Override
    public boolean isPassivating()
    {
        return true;
    }

    @Override
    public String toString()
    {
        return String.format("%s[collection=%s]", super.toString(), getDBCollection());
    }
}