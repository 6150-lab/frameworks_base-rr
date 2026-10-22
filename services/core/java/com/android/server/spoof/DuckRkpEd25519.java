/*
 * Copyright (C) 2026 Resurrection Remix
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.spoof;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * RFC 8032 Ed25519, seed in and signature out. BoringSSL's
 * {@code ED25519_keypair_from_seed} / {@code ED25519_sign} use the same seed.
 */
final class DuckRkpEd25519 {
    private static final BigInteger P =
            BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger L = new BigInteger(
            "7237005577332262213973186563042994240857116359379907606001950938285454250989");
    private static final BigInteger D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger BX = new BigInteger(
            "15112221349535400772501151409588531511454012693041857206046113283949847762202");
    private static final BigInteger BY = new BigInteger(
            "46316835694926478169428394003475163141307993866256225615783033603165251855960");

    private DuckRkpEd25519() {}

    static byte[] publicKey(byte[] seed) throws Exception {
        final byte[] scalar = clampedScalar(seed);
        return encode(scalarmult(new Point(BX, BY), scalar));
    }

    static byte[] sign(byte[] seed, byte[] message) throws Exception {
        final byte[] digest = sha512(seed);
        final byte[] scalar = clampedScalar(seed);
        final byte[] prefix = Arrays.copyOfRange(digest, 32, 64);
        final byte[] publicKey = encode(scalarmult(new Point(BX, BY), scalar));
        final byte[] rBytes = toLe32(leToBig(sha512(concat(prefix, message))).mod(L));
        final byte[] encodedR = encode(scalarmult(new Point(BX, BY), rBytes));
        final BigInteger k = leToBig(sha512(concat(encodedR, publicKey, message))).mod(L);
        final BigInteger s = leToBig(rBytes).add(k.multiply(leToBig(scalar))).mod(L);
        return concat(encodedR, toLe32(s));
    }

    private static byte[] clampedScalar(byte[] seed) throws Exception {
        final byte[] scalar = Arrays.copyOf(sha512(seed), 32);
        scalar[0] = (byte) (scalar[0] & 248);
        scalar[31] = (byte) ((scalar[31] & 63) | 64);
        return scalar;
    }

    private static Point scalarmult(Point point, byte[] scalarLe) {
        Point result = new Point(BigInteger.ZERO, BigInteger.ONE);
        Point base = point;
        for (int bit = 0; bit < 256; bit++) {
            if (((scalarLe[bit >> 3] >> (bit & 7)) & 1) == 1) {
                result = add(result, base);
            }
            base = add(base, base);
        }
        return result;
    }

    private static Point add(Point p, Point q) {
        final BigInteger xx = p.x.multiply(q.x).mod(P);
        final BigInteger yy = p.y.multiply(q.y).mod(P);
        final BigInteger xy = p.x.multiply(q.y).mod(P);
        final BigInteger yx = p.y.multiply(q.x).mod(P);
        final BigInteger product = D.multiply(xx).mod(P).multiply(yy).mod(P);
        final BigInteger x3 = xy.add(yx).mod(P)
                .multiply(BigInteger.ONE.add(product).mod(P).modInverse(P)).mod(P);
        final BigInteger y3 = yy.add(xx).mod(P)
                .multiply(BigInteger.ONE.subtract(product).mod(P).modInverse(P)).mod(P);
        return new Point(x3, y3);
    }

    private static byte[] encode(Point point) {
        final byte[] out = toLe32(point.y);
        if (point.x.testBit(0)) {
            out[31] |= (byte) 0x80;
        }
        return out;
    }

    private static byte[] sha512(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-512").digest(data);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        final byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    private static BigInteger leToBig(byte[] little) {
        final byte[] big = new byte[little.length + 1];
        for (int i = 0; i < little.length; i++) {
            big[little.length - i] = little[i];
        }
        return new BigInteger(big);
    }

    private static byte[] toLe32(BigInteger value) {
        final byte[] big = value.toByteArray();
        final byte[] out = new byte[32];
        for (int i = 0; i < 32 && i < big.length; i++) {
            out[i] = big[big.length - 1 - i];
        }
        return out;
    }

    private static final class Point {
        final BigInteger x;
        final BigInteger y;

        Point(BigInteger x, BigInteger y) {
            this.x = x;
            this.y = y;
        }
    }
}
