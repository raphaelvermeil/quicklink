package com.quicklink.app.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Base62Test {

    @Test
    void encodesZeroAsZero() {
        assertEquals("0", Base62.encode(0));
    }

    @Test
    void encodesSingleDigits() {
        assertEquals("1", Base62.encode(1));
        assertEquals("a", Base62.encode(10));   // digits end, lowercase begins
        assertEquals("z", Base62.encode(35));   // lowercase ends
        assertEquals("A", Base62.encode(36));   // uppercase begins
        assertEquals("Z", Base62.encode(61));   // last symbol in the alphabet
    }

    @Test
    void rollsOverToTwoCharactersAt62() {
        assertEquals("10", Base62.encode(62));
        assertEquals("11", Base62.encode(63));
    }

    @Test
    void encodesFirstSequenceValueAsFiveCharacters() {
        assertEquals("10000", Base62.encode(14776336));   // 62^4, our START WITH
    }

    @Test
    void allCodesFromSequenceStartAreAtLeastFiveCharacters() {
        for (long id = 14776336; id < 14776336 + 1000; id++) {
            assertTrue(Base62.encode(id).length() >= 5,
                       "id " + id + " encoded too short");
        }
    }

    @Test
    void neverProducesDuplicates() {
        Set<String> seen = new HashSet<>();
        for (long id = 0; id < 100_000; id++) {
            seen.add(Base62.encode(id));
        }
        assertEquals(100_000, seen.size());
    }
}
