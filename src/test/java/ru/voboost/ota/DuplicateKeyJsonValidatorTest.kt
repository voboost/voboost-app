package ru.voboost.ota

import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit owner of the strict-JSON guard; contract for every wire parser in this app. */
class DuplicateKeyJsonValidatorTest {
    private fun reject(json: String) {
        org.junit.Assert.assertThrows(ProductContractException::class.java) {
            DuplicateKeyJsonValidator.validate(json)
        }
    }

    private fun accept(json: String) {
        assertTrue(
            "expected accepted: $json",
            runCatching { DuplicateKeyJsonValidator.validate(json) }.isSuccess,
        )
    }

    @Test
    fun rejectsEveryDuplicateAtOneObjectLevelBeforeLastKeyWins() {
        // Plain repeated key: org.json would silently keep the "2".
        reject("""{"id":1,"id":2}""")

        // The same decoded key spelled with a unicode escape: only this
        // guard stands between the wire and last-key-wins.
        reject("{\"\\u0061\":1,\"a\":2}")
        reject("{\"abc\":[true],\"\\u0061bc\":[1]}")
        reject("""{"k":1,"k":2,"k":3}""")

        // Duplicates inside a nested object, not only at the root.
        reject("""{"outer":{"id":1,"id":2},"id":3}""")

        // Duplicates with whitespace padding between tokens.
        reject("""{"id" : 1 , "id" : 2}""")
    }

    @Test
    fun acceptsRepeatedKeysAcrossSeparateObjectsAndLevels() {
        // The same key at different nesting depths and in sibling objects is legal.
        accept("""{"id":1,"rows":[{"id":10},{"id":20}],"other":{"id":30}}""")
        accept("""[{"key":3},{"key":4},{"key":[[{"key":0.5}]]}]""")
        accept("""{"a":{},"b":{"a":{}}}""")
        accept("""[[],[],{}]""")

        // Escaped forms that decode to DISTINCT keys coexist legally.
        accept("""{"a b":1,"a\tb":2}""")

        // Empty containers and blank padding.
        accept("""  {  }  """)
        accept("""  [  ]  """)
    }

    @Test
    fun numberGrammarIsEnforcedWhereOrgJsonIsLenient() {
        reject("""{"a":01,"b":1}""")
        reject("""{"a":-0.}""")
        reject("""{"a":1e}""")
        reject("""{"a":-}""")
        reject("""{"a":+1}""")

        // Legal numeric forms must still pass.
        accept("""{"a":-0,"b":1e+3,"c":1E-3,"d":0.25,"e":0}""")
    }

    @Test
    fun stringControlCharactersAndBadLiteralsAreRejected() {
        // Raw control character (0x01) inside a key string.
        reject("{\"a\u0001b\":1}")
        // Unescaped characters that break the key/value grammar.
        reject("""{"a" "b":1}""")
        reject("""{"a":maybe}""")
        reject("""{"a":tru}""")
        reject("""{a:1}""")

        // Correct escapes decode and are accepted.
        accept("""{"a":"q\"b\\/c\tb"}""")
    }

    @Test
    fun unicodeEscapesRequireFourHexDigits() {
        reject("\"\\u+001\"")
        reject("\"\\u-001\"")
        reject("\"\\u０００１\"")
        reject("\"\\u٠٠٠١\"")
        accept("\"\\u0001\"")
        accept("\"\\uAbCd\\u00ef\"")
    }

    @Test
    fun structuralCloseTrailingAndEscapeErrorsAreRejected() {
        reject("""{"a":1,"b":}""")
        reject("""{"a":1""")
        reject("""{"a":1,"b":2,}""")
        reject("""[1, 2,]""")
        reject("""[""")
        reject("""[}""")
        reject("""{"a":1} trailing""")
        reject("""{}{"a":1}""")
        reject("\"unterminated")
        reject("\"\\u12\"")
        reject("\"\\x41\"")
    }
}
