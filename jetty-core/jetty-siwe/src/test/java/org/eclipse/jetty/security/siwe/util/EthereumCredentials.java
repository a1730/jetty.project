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

package org.eclipse.jetty.security.siwe.util;

import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.security.siwe.EthereumSignatureVerifier;
import org.eclipse.jetty.security.siwe.SignedMessage;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

public class EthereumCredentials
{
    Credentials credentials;

    public EthereumCredentials()
    {
        try
        {
            ECKeyPair keyPair = Keys.createEcKeyPair();
            credentials = Credentials.create(keyPair);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    private ECKeyPair getEcKeyPair()
    {
        return credentials.getEcKeyPair();
    }

    public String getAddress()
    {
        return credentials.getAddress();
    }

    public SignedMessage signMessage(String message)
    {
        byte[] messageBytes = message.getBytes(StandardCharsets.ISO_8859_1);
        String prefix = EthereumSignatureVerifier.PREFIX + messageBytes.length + message;
        byte[] messageHash = EthereumSignatureVerifier.keccak256(prefix.getBytes(StandardCharsets.ISO_8859_1));
        Sign.SignatureData signature = Sign.signMessage(messageHash, credentials.getEcKeyPair(), false);
        String signatureHex = Numeric.toHexString(signature.getR()) +
            Numeric.toHexString(signature.getS()).substring(2) +
            Numeric.toHexString(signature.getV()).substring(2);
        return new SignedMessage(message, signatureHex);
    }
}
