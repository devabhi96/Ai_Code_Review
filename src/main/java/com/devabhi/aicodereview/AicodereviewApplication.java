package com.devabhi.aicodereview;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AicodereviewApplication {

public static void main(String[] args) {
// Postgres rejects the legacy "Asia/Calcutta" zone id; run everything in UTC.
TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
SpringApplication.run(AicodereviewApplication.class, args);
}
}