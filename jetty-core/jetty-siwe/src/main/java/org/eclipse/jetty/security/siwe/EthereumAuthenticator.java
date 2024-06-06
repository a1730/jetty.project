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

package org.eclipse.jetty.security.siwe;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MultiPartFormData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.ByteBufferContentSource;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.security.authentication.SessionAuthentication;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.MultiPartFormFields;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.CharsetStringBuilder.Iso88591StringBuilder;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EthereumAuthenticator extends LoginAuthenticator
{
    private static final Logger LOG = LoggerFactory.getLogger(EthereumAuthenticator.class);

    public static final String LOGIN_PATH_PARAM = "org.eclipse.jetty.security.siwe.login_path";
    public static final String AUTH_PATH_PARAM = "org.eclipse.jetty.security.siwe.auth_path";
    public static final String NONCE_PATH_PARAM = "org.eclipse.jetty.security.siwe.nonce_path";
    public static final String MAX_MESSAGE_SIZE_PARAM = "org.eclipse.jetty.security.siwe.max_message_size";
    public static final String LOGOUT_REDIRECT_PARAM = "org.eclipse.jetty.security.siwe.logout_redirect_path";
    public static final String DISPATCH_PARAM = "org.eclipse.jetty.security.siwe.dispatch";
    public static final String ERROR_PAGE = "org.eclipse.jetty.security.siwe.error_page";
    public static final String J_URI = "org.eclipse.jetty.security.siwe.URI";
    public static final String J_POST = "org.eclipse.jetty.security.siwe.POST";
    public static final String J_METHOD = "org.eclipse.jetty.security.siwe.METHOD";
    public static final String ERROR_PARAMETER = "error_description_jetty";
    private static final String DEFAULT_AUTH_PATH = "/auth/login";
    private static final String DEFAULT_NONCE_PATH = "/auth/nonce";
    private static final String NONCE_SET_ATTR = "org.eclipse.jetty.security.siwe.nonce";

    private final IncludeExcludeSet<String, String> _chainIds = new IncludeExcludeSet<>();
    private final IncludeExcludeSet<String, String> _schemes = new IncludeExcludeSet<>();
    private final IncludeExcludeSet<String, String> _domains = new IncludeExcludeSet<>();

    private String _loginPath;
    private String _authPath = DEFAULT_AUTH_PATH;
    private String _noncePath = DEFAULT_NONCE_PATH;
    private long _maxMessageSize = 4 * 1024;
    private String _logoutRedirectPath;
    private String _errorPage;
    private String _errorPath;
    private String _errorQuery;
    private boolean _dispatch;

    public EthereumAuthenticator()
    {
    }

    public void includeDomains(String... domains)
    {
        _domains.include(domains);
    }

    public void includeSchemes(String... schemes)
    {
        _schemes.include(schemes);
    }

    public void includeChainIds(String... chainIds)
    {
        _chainIds.include(chainIds);
    }

    @Override
    public void setConfiguration(Authenticator.Configuration authConfig)
    {
        String loginPath = authConfig.getParameter(LOGIN_PATH_PARAM);
        if (loginPath != null)
            setLoginPath(loginPath);

        String authPath = authConfig.getParameter(AUTH_PATH_PARAM);
        if (authPath != null)
            setAuthPath(authPath);

        String noncePath = authConfig.getParameter(NONCE_PATH_PARAM);
        if (noncePath != null)
            setNoncePath(noncePath);

        String maxMessageSize = authConfig.getParameter(MAX_MESSAGE_SIZE_PARAM);
        if (maxMessageSize != null)
            setMaxMessageSize(Integer.parseInt(maxMessageSize));

        String logout = authConfig.getParameter(LOGOUT_REDIRECT_PARAM);
        if (logout != null)
            setLogoutRedirectPath(logout);

        String error = authConfig.getParameter(ERROR_PAGE);
        if (error != null)
            setErrorPage(error);

        String dispatch = authConfig.getParameter(DISPATCH_PARAM);
        if (dispatch != null)
            setDispatch(Boolean.parseBoolean(dispatch));

        // If no LoginService is set we allow any user to log in.
        if (authConfig.getLoginService() == null)
        {
            LoginService loginService = new AnyUserLoginService();
            authConfig = new Configuration.Wrapper(authConfig)
            {
                @Override
                public LoginService getLoginService()
                {
                    return loginService;
                }
            };
        }

        super.setConfiguration(authConfig);
    }

    @Override
    public String getAuthenticationType()
    {
        return Authenticator.SIWE_AUTH;
    }

    public void setLoginPath(String loginPath)
    {
        if (loginPath == null)
        {
            LOG.warn("login path must not be null, defaulting to " + _loginPath);
            loginPath = _loginPath;
        }
        else if (!loginPath.startsWith("/"))
        {
            LOG.warn("login path must start with /");
            loginPath = "/" + loginPath;
        }

        _loginPath = loginPath;
    }

    public void setAuthPath(String authPath)
    {
        if (authPath == null)
        {
            authPath = _authPath;
            LOG.warn("login path must not be null, defaulting to " + authPath);
        }
        else if (!authPath.startsWith("/"))
        {
            authPath = "/" + authPath;
            LOG.warn("login path must start with /");
        }

        _authPath = authPath;
    }

    public void setNoncePath(String noncePath)
    {
        if (noncePath == null)
        {
            noncePath = _noncePath;
            LOG.warn("login path must not be null, defaulting to " + noncePath);
        }
        else if (!noncePath.startsWith("/"))
        {
            noncePath = "/" + noncePath;
            LOG.warn("login path must start with /");
        }

        _noncePath = noncePath;
    }

    public void setMaxMessageSize(int maxMessageSize)
    {
        _maxMessageSize = maxMessageSize;
    }

    public void setDispatch(boolean dispatch)
    {
        _dispatch = dispatch;
    }

    public void setLogoutRedirectPath(String logoutRedirectPath)
    {
        if (logoutRedirectPath == null)
        {
            LOG.warn("logout redirect path must not be null, defaulting to /");
            logoutRedirectPath = "/";
        }
        else if (!logoutRedirectPath.startsWith("/"))
        {
            LOG.warn("logout redirect path must start with /");
            logoutRedirectPath = "/" + logoutRedirectPath;
        }

        _logoutRedirectPath = logoutRedirectPath;
    }

    public void setErrorPage(String path)
    {
        if (path == null || path.trim().isEmpty())
        {
            _errorPath = null;
            _errorPage = null;
        }
        else
        {
            if (!path.startsWith("/"))
            {
                LOG.warn("error-page must start with /");
                path = "/" + path;
            }
            _errorPage = path;
            _errorPath = path;
            _errorQuery = "";

            int queryIndex = _errorPath.indexOf('?');
            if (queryIndex > 0)
            {
                _errorPath = _errorPage.substring(0, queryIndex);
                _errorQuery = _errorPage.substring(queryIndex + 1);
            }
        }
    }

    @Override
    public UserIdentity login(String username, Object credentials, Request request, Response response)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("login {} {} {}", username, credentials, request);

        UserIdentity user = super.login(username, credentials, request, response);
        if (user != null)
        {
            Session session = request.getSession(true);
            AuthenticationState cached = new SessionAuthentication(getAuthenticationType(), user, credentials);
            synchronized (session)
            {
                session.setAttribute(SessionAuthentication.AUTHENTICATED_ATTRIBUTE, cached);
            }
        }
        return user;
    }

    @Override
    public void logout(Request request, Response response)
    {
        attemptLogoutRedirect(request, response);
        logoutWithoutRedirect(request, response);
    }

    private void logoutWithoutRedirect(Request request, Response response)
    {
        super.logout(request, response);
        Session session = request.getSession(false);
        if (session == null)
            return;
        synchronized (session)
        {
            session.removeAttribute(SessionAuthentication.AUTHENTICATED_ATTRIBUTE);
        }
    }

    /**
     * <p>This will attempt to redirect the request to the {@link #_logoutRedirectPath}.</p>
     *
     * @param request the request to redirect.
     */
    private void attemptLogoutRedirect(Request request, Response response)
    {
        try
        {
            String redirectUri = null;
            if (_logoutRedirectPath != null)
            {
                HttpURI.Mutable httpURI = HttpURI.build()
                    .scheme(request.getHttpURI().getScheme())
                    .host(Request.getServerName(request))
                    .port(Request.getServerPort(request))
                    .path(URIUtil.compactPath(Request.getContextPath(request) + _logoutRedirectPath));
                redirectUri = httpURI.toString();
            }

            Session session = request.getSession(false);
            if (session == null)
            {
                if (redirectUri != null)
                    sendRedirect(request, response, redirectUri);
            }
        }
        catch (Throwable t)
        {
            LOG.warn("failed to redirect to end_session_endpoint", t);
        }
    }

    private void sendRedirect(Request request, Response response, String location) throws IOException
    {
        try (Blocker.Callback callback = Blocker.callback())
        {
            Response.sendRedirect(request, response, callback, location);
            callback.block();
        }
    }

    @Override
    public Request prepareRequest(Request request, AuthenticationState authenticationState)
    {
        // if this is a request resulting from a redirect after auth is complete
        // (ie its from a redirect to the original request uri) then due to
        // browser handling of 302 redirects, the method may not be the same as
        // that of the original request. Replace the method and original post
        // params (if it was a post).
        if (authenticationState instanceof AuthenticationState.Succeeded)
        {
            Session session = request.getSession(false);
            if (session == null)
                return request; //not authenticated yet

            // Remove the nonce set used for authentication.
            session.removeAttribute(NONCE_SET_ATTR);

            HttpURI juri = (HttpURI)session.getAttribute(J_URI);
            HttpURI uri = request.getHttpURI();
            if ((uri.equals(juri)))
            {
                session.removeAttribute(J_URI);

                Fields fields = (Fields)session.removeAttribute(J_POST);
                if (fields != null)
                    request.setAttribute(FormFields.class.getName(), fields);

                String method = (String)session.removeAttribute(J_METHOD);
                if (method != null && request.getMethod().equals(method))
                {
                    return new Request.Wrapper(request)
                    {
                        @Override
                        public String getMethod()
                        {
                            return method;
                        }
                    };
                }
            }
        }

        return request;
    }

    @Override
    public Constraint.Authorization getConstraintAuthentication(String pathInContext, Constraint.Authorization existing, Function<Boolean, Session> getSession)
    {
        if (isAuthenticationRequest(pathInContext))
            return Constraint.Authorization.ANY_USER;
        if (isLoginPage(pathInContext) || isErrorPage(pathInContext))
            return Constraint.Authorization.ALLOWED;
        return existing;
    }

    protected String readMessage(InputStream in) throws IOException
    {
        Iso88591StringBuilder out = new Iso88591StringBuilder();
        byte[] buffer = new byte[1024];
        int totalRead = 0;

        while (true)
        {
            int len = in.read(buffer, 0, buffer.length);
            if (len < 0)
                break;

            totalRead += len;
            if (totalRead > _maxMessageSize)
                throw new BadMessageException("SIWE Message Too Large");
            out.append(buffer, 0, len);
        }

        return out.build();
    }

    protected SignedMessage parseMessage(Request request, Response response, Callback callback)
    {
        try
        {
            InputStream inputStream = Content.Source.asInputStream(request);
            String requestContent = readMessage(inputStream);

            MultiPartFormFields.Config config = new MultiPartFormFields.Config(10, 1024 * 8, -1, -1, null, null);
            MultiPartFormData.Parts parts = MultiPartFormFields.from(request, new ByteBufferContentSource(BufferUtil.toBuffer(requestContent)), config).get();

            String signature = parts.getFirst("signature").getContentAsString(StandardCharsets.ISO_8859_1);
            String message = parts.getFirst("message").getContentAsString(StandardCharsets.ISO_8859_1);

            // The browser may convert LF to CRLF, EIP4361 specifies to only use LF.
            message = message.replace("\r\n", "\n");

            return new SignedMessage(message, signature);
        }
        catch (Throwable t)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("error reading SIWE message and signature", t);
            sendError(request, response, callback, t.getMessage());
            return null;
        }
    }

    protected AuthenticationState handleNonceRequest(Request request, Response response, Callback callback)
    {
        String nonce = createNonce(request.getSession(false));
        ByteBuffer content = BufferUtil.toBuffer("{ \"nonce\": \"" + nonce + "\" }");
        response.write(true, content, callback);
        return AuthenticationState.CHALLENGE;
    }

    private boolean validateSignInWithEthereumToken(SignInWithEthereumToken siwe, SignedMessage signedMessage, Request request, Response response, Callback callback)
    {
        Session session = request.getSession(false);
        if (siwe == null)
        {
            sendError(request, response, callback, "failed to parse SIWE message");
            return false;
        }

        try
        {
            siwe.validate(signedMessage, nonce -> redeemNonce(session, nonce), _schemes, _domains, _chainIds);
        }
        catch (Throwable t)
        {
            sendError(request, response, callback, t.getMessage());
            return false;
        }

        return true;
    }

    @Override
    public AuthenticationState validateRequest(Request request, Response response, Callback callback) throws ServerAuthException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("validateRequest({},{})", request, response);

        String uri = request.getHttpURI().toString();
        if (uri == null)
            uri = "/";

        try
        {
            Session session = request.getSession(false);
            if (session == null)
            {
                session = request.getSession(true);
                if (session == null)
                {
                    sendError(request, response, callback, "session could not be created");
                    return AuthenticationState.SEND_FAILURE;
                }
            }

//            boolean sessionIdFromCookie = (Boolean)request.getAttribute("org.eclipse.jetty.session.sessionIdFromCookie");
//            if (!sessionIdFromCookie)
//            {
//                sendError(request, response, callback, "Session ID must be a cookie to support SIWE authentication");
//                return AuthenticationState.SEND_FAILURE;
//            }

            if (isNonceRequest(uri))
                return handleNonceRequest(request, response, callback);
            if (isAuthenticationRequest(uri))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("authentication request");

                // Parse and validate SIWE Message.
                SignedMessage signedMessage = parseMessage(request, response, callback);
                if (signedMessage == null)
                    return AuthenticationState.SEND_FAILURE;
                SignInWithEthereumToken siwe = SignInWithEthereumParser.parse(signedMessage.message());
                if (siwe == null || !validateSignInWithEthereumToken(siwe, signedMessage, request, response, callback))
                    return AuthenticationState.SEND_FAILURE;

                String address = siwe.address();
                UserIdentity user = login(address, null, request, response);
                if (LOG.isDebugEnabled())
                    LOG.debug("user identity: {}", user);
                if (user != null)
                {
                    // Redirect to original request
                    HttpURI savedURI = (HttpURI)session.getAttribute(J_URI);
                    String originalURI = savedURI != null
                        ? savedURI.getPathQuery()
                        : Request.getContextPath(request);
                    if (originalURI == null)
                        originalURI = "/";
                    UserAuthenticationSent formAuth = new UserAuthenticationSent(getAuthenticationType(), user);
                    String redirectUrl = session.encodeURI(request, originalURI, true);
                    Response.sendRedirect(request, response, callback, redirectUrl, true);
                    return formAuth;
                }

                // not authenticated
                if (LOG.isDebugEnabled())
                    LOG.debug("auth failed {}=={}", address, _errorPage);
                sendError(request, response, callback, "auth failed");
                return AuthenticationState.SEND_FAILURE;
            }

            // Look for cached authentication in the Session.
            AuthenticationState authenticationState = (AuthenticationState)session.getAttribute(SessionAuthentication.AUTHENTICATED_ATTRIBUTE);
            if (authenticationState != null)
            {
                // Has authentication been revoked?
                if (authenticationState instanceof AuthenticationState.Succeeded && _loginService != null &&
                    !_loginService.validate(((AuthenticationState.Succeeded)authenticationState).getUserIdentity()))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("auth revoked {}", authenticationState);
                    logoutWithoutRedirect(request, response);
                    return AuthenticationState.SEND_FAILURE;
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("auth {}", authenticationState);
                return authenticationState;
            }

            // If we can't send challenge.
            if (AuthenticationState.Deferred.isDeferred(response))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("auth deferred {}", session.getId());
                return null;
            }

            // Save the current URI
            synchronized (session)
            {
                // But only if it is not set already, or we save every uri that leads to a login form redirect
                if (session.getAttribute(J_URI) == null)
                {
                    HttpURI juri = request.getHttpURI();
                    session.setAttribute(J_URI, juri.asImmutable());
                    if (!HttpMethod.GET.is(request.getMethod()))
                        session.setAttribute(J_METHOD, request.getMethod());
                    if (HttpMethod.POST.is(request.getMethod()))
                        session.setAttribute(J_POST, getParameters(request));
                }
            }

            // Send the challenge.
            String loginPath = URIUtil.addPaths(request.getContext().getContextPath(), _loginPath);
            if (_dispatch)
            {
                HttpURI.Mutable newUri = HttpURI.build(request.getHttpURI()).pathQuery(loginPath);
                return new AuthenticationState.ServeAs(newUri);
            }
            else
            {
                String redirectUri = session.encodeURI(request, loginPath, true);
                Response.sendRedirect(request, response, callback, redirectUri, true);
                return AuthenticationState.CHALLENGE;
            }
        }
        catch (Throwable t)
        {
            throw new ServerAuthException(t);
        }
    }

    /**
     * Report an error case either by redirecting to the error page if it is defined, otherwise sending a 403 response.
     * If the message parameter is not null, a query parameter with a key of {@link #ERROR_PARAMETER} and value of the error
     * message will be logged and added to the error redirect URI if the error page is defined.
     * @param request the request.
     * @param response the response.
     * @param message the reason for the error or null.
     */
    private void sendError(Request request, Response response, Callback callback, String message)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("OpenId authentication FAILED: {}", message);

        if (_errorPage == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("auth failed 403");
            if (response != null)
                Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403, message);
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("auth failed {}", _errorPage);

            String contextPath = Request.getContextPath(request);
            String redirectUri = URIUtil.addPaths(contextPath, _errorPage);
            if (message != null)
            {
                String query = URIUtil.addQueries(ERROR_PARAMETER + "=" + UrlEncoded.encodeString(message), _errorQuery);
                redirectUri = URIUtil.addPathQuery(URIUtil.addPaths(contextPath, _errorPath), query);
            }

            int redirectCode = request.getConnectionMetaData().getHttpVersion().getVersion() < HttpVersion.HTTP_1_1.getVersion()
                ? HttpStatus.MOVED_TEMPORARILY_302 : HttpStatus.SEE_OTHER_303;
            Response.sendRedirect(request, response, callback, redirectCode, redirectUri, true);
        }
    }

    protected Fields getParameters(Request request)
    {
        try
        {
            Fields queryFields = Request.extractQueryParameters(request);
            Fields formFields = FormFields.from(request).get();
            return Fields.combine(queryFields, formFields);
        }
        catch (InterruptedException | ExecutionException e)
        {
            throw new RuntimeException(e);
        }
    }

    public boolean isLoginPage(String uri)
    {
        return matchURI(uri, _loginPath);
    }

    public boolean isAuthenticationRequest(String uri)
    {
        return matchURI(uri, _authPath);
    }

    public boolean isNonceRequest(String uri)
    {
        return matchURI(uri, _noncePath);
    }

    private boolean matchURI(String uri, String path)
    {
        int jsc = uri.indexOf(path);
        if (jsc < 0)
            return false;
        int e = jsc + path.length();
        if (e == uri.length())
            return true;
        char c = uri.charAt(e);
        return c == ';' || c == '#' || c == '/' || c == '?';
    }

    public boolean isErrorPage(String pathInContext)
    {
        return pathInContext != null && (pathInContext.equals(_errorPath));
    }

    protected String createNonce(Session session)
    {
        String nonce = EthereumUtil.createNonce();
        synchronized (session)
        {
            @SuppressWarnings("unchecked")
            Set<String> attribute = (Set<String>)session.getAttribute(NONCE_SET_ATTR);
            if (attribute == null)
                session.setAttribute(NONCE_SET_ATTR, attribute = new FixedSizeSet<>(5));
            if (!attribute.add(nonce))
                throw new IllegalStateException("Nonce already in use");
        }
        return nonce;
    }

    protected boolean redeemNonce(Session session, String nonce)
    {
        synchronized (session)
        {
            @SuppressWarnings("unchecked")
            Set<String> attribute = (Set<String>)session.getAttribute(NONCE_SET_ATTR);
            if (attribute == null)
                return false;
            return attribute.remove(nonce);
        }
    }

    public static class FixedSizeSet<T> extends LinkedHashSet<T>
    {
        private final int maxSize;

        public FixedSizeSet(int maxSize)
        {
            super(maxSize);
            this.maxSize = maxSize;
        }

        @Override
        public boolean add(T element)
        {
            if (size() >= maxSize)
            {
                Iterator<T> it = iterator();
                if (it.hasNext())
                {
                    it.next();
                    it.remove();
                }
            }
            return super.add(element);
        }
    }
}
