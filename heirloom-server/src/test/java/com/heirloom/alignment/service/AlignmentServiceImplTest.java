package com.heirloom.alignment.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AlignmentServiceImplTest {
    @Test
    void shouldMatchIdenticalNames() {
        assertTrue(AlignmentServiceImpl.isNameSimilar("email", "email"));
    }

    @Test
    void shouldMatchUnderscoreInsensitive() {
        assertTrue(AlignmentServiceImpl.isNameSimilar("customer_email", "customeremail"));
    }

    @Test
    void shouldMatchSubstring() {
        assertTrue(AlignmentServiceImpl.isNameSimilar("phone_number", "phone"));
    }

    @Test
    void shouldNotMatchUnrelated() {
        assertFalse(AlignmentServiceImpl.isNameSimilar("customer_name", "order_amount"));
    }
}
