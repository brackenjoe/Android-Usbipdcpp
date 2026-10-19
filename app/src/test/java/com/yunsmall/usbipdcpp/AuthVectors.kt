package com.yunsmall.usbipdcpp

/**
 * Test vectors from the Python reference, `_tag` in
 * linux/usbip-dialin/usbip_dialin.py (this repository's copy). Regenerate, from
 * the repository root, with
 *
 *     python3 -I app/src/test/resources/gen_auth_vectors.py \
 *         linux/usbip-dialin/usbip_dialin.py
 *
 * and paste the output below. AuthVectorsTest runs that command when python3
 * and the reference are present and fails if the output differs from this list.
 * Fake keys and nonces only.
 */
data class Vector(
    val key: String,
    val nonceB: String,
    val nonceP: String,
    val phoneTag: String,
    val boxTag: String
)

val AUTH_VECTORS = listOf(
    Vector(
        key = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
        nonceB = "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f",
        nonceP = "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f",
        phoneTag = "920745aef4be5a2aa634018c66646421776a0c81a3924accbd05f4d4fd437e13",
        boxTag = "b9b09ca3ac02932175e371b0366ddc58434bbaba45789fdb17172de763b6a152"
    ),
    Vector(
        key = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        nonceB = "0000000000000000000000000000000000000000000000000000000000000000",
        nonceP = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        phoneTag = "ac36be692cbb079e064b534ff2c272a76ca644e17207f221b123b258dbe0cd7b",
        boxTag = "8cf0a86740fc6102f1e64880e5e278a80d0d8de13f64415a8aa426e35bbf1adb"
    ),
    Vector(
        key = "e0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff",
        nonceB = "1111111111111111111111111111111111111111111111111111111111111101",
        nonceP = "1111111111111111111111111111111111111111111111111111111111111102",
        phoneTag = "37a4c9e6d483004217ae5a7631060a839e5e5216d4ce9357bf722ab55955692c",
        boxTag = "5b2d73657bb532c2f8c58989655b93fae86441f1d24bd49fbd880b41280aee6a"
    )
)
