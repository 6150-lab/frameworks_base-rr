/*
 * Copyright (C) 2026 Resurrection Remix
 *
 * Duck RKP follows Duck-ToolBox by Eltavine (MIT),
 * https://github.com/eltavine/Duck-ToolBox
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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Software CSR used when the KeyMint HAL is older than version 3. The DICE
 * key is derived from a zero HBK. Device info matches the 14 fields a KeyMint
 * 3 HAL puts in a CSR that Google will accept.
 */
final class DuckRkpCsr {
    private static final byte[] EDDSA_PROTECTED = new byte[] {(byte) 0xa1, 0x01, 0x27};

    private DuckRkpCsr() {}

    static byte[] deviceInfo(String brand, String manufacturer, String product, String model,
            String device, String vbState, String bootloaderState, byte[] vbmetaDigest,
            String osVersion, int systemPatchLevel, int bootPatchLevel, int vendorPatchLevel) {
        final ByteArrayOutputStream info = new ByteArrayOutputStream();
        info.write(0xae);
        writeTstr(info, "brand");
        writeTstr(info, brand);
        writeTstr(info, "fused");
        writeUint(info, 1);
        writeTstr(info, "model");
        writeTstr(info, model);
        writeTstr(info, "device");
        writeTstr(info, device);
        writeTstr(info, "product");
        writeTstr(info, product);
        writeTstr(info, "vb_state");
        writeTstr(info, vbState);
        writeTstr(info, "os_version");
        writeTstr(info, osVersion);
        writeTstr(info, "manufacturer");
        writeTstr(info, manufacturer);
        writeTstr(info, "vbmeta_digest");
        writeBstr(info, vbmetaDigest);
        writeTstr(info, "security_level");
        writeTstr(info, "tee");
        writeTstr(info, "boot_patch_level");
        writeUint(info, bootPatchLevel);
        writeTstr(info, "bootloader_state");
        writeTstr(info, bootloaderState);
        writeTstr(info, "system_patch_level");
        writeUint(info, systemPatchLevel);
        writeTstr(info, "vendor_patch_level");
        writeUint(info, vendorPatchLevel);
        return info.toByteArray();
    }

    static byte[] build(byte[] p256CoseKey, byte[] challenge, String fingerprint, byte[] seed,
            byte[] deviceInfo) throws Exception {
        final byte[] bcc = buildBcc(seed);
        final ByteArrayOutputStream csrPayload = new ByteArrayOutputStream();
        csrPayload.write(0x84);
        csrPayload.write(0x03);
        writeTstr(csrPayload, "keymint");
        csrPayload.write(deviceInfo);
        csrPayload.write(0x81);
        csrPayload.write(p256CoseKey);

        final ByteArrayOutputStream signedPayload = new ByteArrayOutputStream();
        signedPayload.write(0x82);
        writeBstr(signedPayload, challenge);
        writeBstr(signedPayload, csrPayload.toByteArray());

        final ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write(0x85);
        request.write(0x01);
        request.write(0xa0);
        request.write(bcc);
        request.write(eddsaSign1(signedPayload.toByteArray(), seed));
        request.write(0xa1);
        writeTstr(request, "fingerprint");
        writeTstr(request, fingerprint);
        return request.toByteArray();
    }

    private static byte[] buildBcc(byte[] seed) throws Exception {
        final byte[] coseKey = ed25519CoseKey(DuckRkpEd25519.publicKey(seed));
        final ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(0xa4);
        payload.write(0x01);
        writeTstr(payload, "Issuer");
        payload.write(0x02);
        writeTstr(payload, "Subject");
        writeNegInt(payload, -4670552);
        writeBstr(payload, coseKey);
        writeNegInt(payload, -4670553);
        writeBstr(payload, new byte[] {0x20});

        final ByteArrayOutputStream bcc = new ByteArrayOutputStream();
        bcc.write(0x82);
        bcc.write(coseKey);
        bcc.write(eddsaSign1(payload.toByteArray(), seed));
        return bcc.toByteArray();
    }

    private static byte[] ed25519CoseKey(byte[] publicKey) {
        final ByteArrayOutputStream key = new ByteArrayOutputStream();
        key.write(0xa4);
        key.write(0x01);
        key.write(0x01);
        key.write(0x03);
        key.write(0x27);
        key.write(0x20);
        key.write(0x06);
        key.write(0x21);
        writeBstr(key, publicKey);
        return key.toByteArray();
    }

    private static byte[] eddsaSign1(byte[] payload, byte[] seed) throws Exception {
        final ByteArrayOutputStream toSign = new ByteArrayOutputStream();
        toSign.write(0x84);
        writeTstr(toSign, "Signature1");
        writeBstr(toSign, EDDSA_PROTECTED);
        writeBstr(toSign, new byte[0]);
        writeBstr(toSign, payload);
        final byte[] signature = DuckRkpEd25519.sign(seed, toSign.toByteArray());

        final ByteArrayOutputStream sign1 = new ByteArrayOutputStream();
        sign1.write(0x84);
        writeBstr(sign1, EDDSA_PROTECTED);
        sign1.write(0xa0);
        writeBstr(sign1, payload);
        writeBstr(sign1, signature);
        return sign1.toByteArray();
    }

    private static void writeNegInt(ByteArrayOutputStream out, long value) {
        final long argument = -1L - value;
        if (argument < 24) {
            out.write(0x20 | (int) argument);
        } else if (argument < 256) {
            out.write(0x38);
            out.write((int) argument);
        } else if (argument < 65536) {
            out.write(0x39);
            out.write((int) (argument >> 8));
            out.write((int) argument);
        } else {
            out.write(0x3a);
            out.write((int) (argument >> 24));
            out.write((int) (argument >> 16));
            out.write((int) (argument >> 8));
            out.write((int) argument);
        }
    }

    private static void writeUint(ByteArrayOutputStream out, int value) {
        if (value < 24) {
            out.write(value);
        } else if (value < 256) {
            out.write(0x18);
            out.write(value);
        } else if (value < 65536) {
            out.write(0x19);
            out.write(value >> 8);
            out.write(value);
        } else {
            out.write(0x1a);
            out.write(value >>> 24);
            out.write(value >>> 16);
            out.write(value >>> 8);
            out.write(value);
        }
    }

    private static void writeBstr(ByteArrayOutputStream out, byte[] bytes) {
        if (bytes.length < 24) {
            out.write(0x40 | bytes.length);
        } else if (bytes.length < 256) {
            out.write(0x58);
            out.write(bytes.length);
        } else {
            out.write(0x59);
            out.write(bytes.length >> 8);
            out.write(bytes.length & 0xff);
        }
        out.write(bytes, 0, bytes.length);
    }

    private static void writeTstr(ByteArrayOutputStream out, String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 24) {
            out.write(0x60 | bytes.length);
        } else if (bytes.length < 256) {
            out.write(0x78);
            out.write(bytes.length);
        } else {
            out.write(0x79);
            out.write(bytes.length >> 8);
            out.write(bytes.length & 0xff);
        }
        out.write(bytes, 0, bytes.length);
    }
}
