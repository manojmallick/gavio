package io.gavio.interceptors.guardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.Gateway;
import io.gavio.GavioException.GuardrailViolationException;
import io.gavio.GavioRequest;
import io.gavio.providers.MockProvider;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class LicenseDetectorValidatorTest {

    // Synthetic license snippets — fixtures only, mirror test-vectors/license.
    private static final String MIT =
            "Permission is hereby granted, free of charge, to any person obtaining a copy of "
                    + "this software and associated documentation files (the \"Software\"), to deal in "
                    + "the Software without restriction, including without limitation the rights to use, "
                    + "copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software.";
    private static final String APACHE =
            "Licensed under the Apache License, Version 2.0 (the \"License\"); you may not use this "
                    + "file except in compliance with the License. You may obtain a copy of the License at.";
    private static final String GPL3 =
            "This program is free software: you can redistribute it and/or modify it under the terms "
                    + "of the GNU General Public License as published by the Free Software Foundation, either "
                    + "version 3 of the License, or (at your option) any later version.";
    private static final String GPL2 =
            "This program is free software; you can redistribute it and/or modify it under the terms "
                    + "of the GNU General Public License as published by the Free Software Foundation; either "
                    + "version 2 of the License, or (at your option) any later version.";
    private static final String CLEAN =
            "int add(int a, int b) { return a + b; } // sums two numbers used across the project";

    private static Gateway gw(String response, LicenseDetectorValidator v, GuardrailsInterceptor.OnFailure onFailure) {
        return Gateway.builder()
                .adapter(new MockProvider(response))
                .model("mock")
                .use(GuardrailsInterceptor.builder().validator(v).onFailure(onFailure).build())
                .build();
    }

    private static GavioRequest req() {
        return GavioRequest.builder().message("user", "q").model("mock").build();
    }

    @Test
    void detectsEachLicense() {
        LicenseDetectorValidator v = new LicenseDetectorValidator();
        assertEquals(List.of("MIT"), v.detect(MIT));
        assertEquals(List.of("Apache-2.0"), v.detect(APACHE));
        assertEquals(List.of("GPL-3.0"), v.detect(GPL3));
        assertEquals(List.of("GPL-2.0"), v.detect(GPL2));
    }

    @Test
    void gplVersionsNotConfused() {
        LicenseDetectorValidator v = new LicenseDetectorValidator();
        assertFalse(v.detect(GPL2).contains("GPL-3.0"));
        assertFalse(v.detect(GPL3).contains("GPL-2.0"));
    }

    @Test
    void cleanContentDetectsNothing() {
        assertEquals(List.of(), new LicenseDetectorValidator().detect(CLEAN));
    }

    @Test
    void multipleLicensesSorted() {
        assertEquals(List.of("Apache-2.0", "MIT"), new LicenseDetectorValidator().detect(MIT + "\n\n" + APACHE));
    }

    @Test
    void validatorNameAndReason() {
        LicenseDetectorValidator v = new LicenseDetectorValidator();
        assertEquals("license_detector", v.name());
        assertTrue(v.validate(CLEAN).ok());
        ValidationResult res = v.validate(MIT);
        assertFalse(res.ok());
        assertEquals("license text detected: MIT", res.reason());
    }

    @Test
    void guardrailsBlocksLicenseText() {
        Gateway gw = gw(MIT, new LicenseDetectorValidator(), GuardrailsInterceptor.OnFailure.ERROR);
        CompletionException ex = assertThrows(CompletionException.class, () -> gw.complete(req()).join());
        assertInstanceOf(GuardrailViolationException.class, ex.getCause());
    }

    @Test
    void warnModeReturnsResponse() {
        Gateway gw = gw(APACHE, new LicenseDetectorValidator(), GuardrailsInterceptor.OnFailure.WARN);
        assertEquals(APACHE, gw.complete(req()).join().content());
    }
}
