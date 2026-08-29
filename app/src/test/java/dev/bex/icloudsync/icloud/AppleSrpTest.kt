package dev.bex.icloudsync.icloud

import org.junit.Assert.assertEquals
import org.junit.Test

class AppleSrpTest {
    @Test
    fun `matches the maintained py-srp Apple parameter vector`() {
        val secret = ByteArray(256).also { bytes ->
            bytes[0] = 0x80.toByte()
            for (index in 1 until bytes.size) bytes[index] = index.toByte()
        }
        val srp = AppleSrp("person@example.com", secret)

        assertEquals(
            "SEaSepx/3qbZ085yrJ+74CyXs2u2hueiriuAWTeGqK9ABmzfh6OY9gjGCNE1RiuKf2bF4fdvH1VFhCTO5rSaIdMzw5q6GVa5/HmrVXTEZmh5aTXaVEsqxrfeXym9B3UdDKKJTrF6Tjdg7Y+dzUYPrO5LOsJ4KfMiyTYRYFHNGXjs3RwOB3h/D6jD2OSwaKvZS1fiQGhj6eDHdx2ke6RSVYObi3oH21PMibPHQQ1UjKpfH+EluT9/PCOh6bobF0n92jN8xSO7/QZUnpqK1qHCLymQq2ppVJoX/7SE8QEskrKGVHTeFP/ymjGaLUZQSqPLy+uKBH1Sp4TletZ2C08KHA==",
            srp.publicA(),
        )
        val proof = srp.complete(
            password = "correct horse battery staple",
            saltBase64 = "ABEiM0RVZneImaq7zN3u/w==",
            serverBBase64 = "oyUMBflfHo9z0EuMMdIESjNvrDm8IYX703VbjR5l1LhphSmwgPfuE5Eu0KUtCSxQNJekbLqP6zBiZhAaGKqs+ASVUNsARX9/YLirP0gMRqSzJbQ+96l5eI9A0OPOYBbSBofbxj3I9whum+NDT0N3eZVadxUzhcSx2WpToVUi05O4wNSUZSe0FG1gCyZV0k90QpKNrVg8tsuvRcAGBd1JVRUY06BvCT39ovuH+WQgL1iCRcYqy7pB/mT3LIC4HGMVMA5yM6dU0USZ2nus5PcvVJxbzZ5zVHh7B0ghSSoCfIdzWpnyr6TxLTzFalwQpYtosraGsqDKnJqQqcv7gswlcw==",
            iterations = 1_000,
            protocol = "s2k",
        )

        assertEquals("PUAInWGOV4BTdbmt5vdD2dO23MUhDOfO3A1k3r44HpI=", proof.m1)
        assertEquals("R1JFfTEr1TZ2zdgI46l5v94wbdrN5Y5uOc277zf0m6c=", proof.m2)
    }
}

