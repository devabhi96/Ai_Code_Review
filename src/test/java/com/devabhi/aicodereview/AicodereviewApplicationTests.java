package com.devabhi.aicodereview;

import java.util.TimeZone;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AicodereviewApplicationTests {

@BeforeAll
static void useUtc() {
// Postgres rejects the legacy "Asia/Calcutta" zone id.
TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
}

@Test
void contextLoads() {
}

}