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

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.eclipse.jetty.util.StringUtil;
import org.web3j.crypto.ECDSASignature;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;

public class EthereumSignatureVerifier
{
    public static final String PREFIX = "\u0019Ethereum Signed Message:\n";

    private EthereumSignatureVerifier()
    {
    }

    public static String recoverAddress(String siweMessage, String signatureHex)
    {
        byte[] bytes = siweMessage.getBytes(StandardCharsets.ISO_8859_1);
        int messageLength = bytes.length;
        String signedMessage = PREFIX + messageLength + siweMessage;
        byte[] messageHash = keccak256(signedMessage.getBytes(StandardCharsets.ISO_8859_1));

        if (StringUtil.asciiStartsWithIgnoreCase(signatureHex, "0x"))
            signatureHex = signatureHex.substring(2);
        byte[] signatureBytes = StringUtil.fromHexString(signatureHex);
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(signatureBytes, 0, 32));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(signatureBytes, 32, 64));
        byte v = (byte)(signatureBytes[64] < 27 ? signatureBytes[64] : signatureBytes[64] - 27);

        BigInteger publicKey = Sign.recoverFromSignature(v, new ECDSASignature(r, s), messageHash);
        return "0x" + Keys.getAddress(publicKey);
    }

    public static byte[] keccak256(byte[] bytes)
    {
        Keccak.Digest256 digest256 = new Keccak.Digest256();
        return digest256.digest(bytes);
    }
}
