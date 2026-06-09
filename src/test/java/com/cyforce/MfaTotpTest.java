package com.cyforce;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MfaTotpTest {

    @Test
    void totpRoundTripWithSamstevens() throws Exception {
        String secret = new DefaultSecretGenerator().generate();
        TimeProvider timeProvider = new SystemTimeProvider();
        CodeGenerator codeGenerator = new DefaultCodeGenerator();
        long bucket = Math.floorDiv(timeProvider.getTime(), 30);
        String code = codeGenerator.generate(secret, bucket);

        DefaultCodeVerifier verifier = new DefaultCodeVerifier(codeGenerator, timeProvider);
        verifier.setTimePeriod(30);
        verifier.setAllowedTimePeriodDiscrepancy(5);

        assertTrue(verifier.isValidCode(secret, code), "Expected code " + code + " to verify for secret " + secret);
    }

    @Test
    void totpRoundTripWithKnownSecret() throws Exception {
        String secret = "TR3T4XRL2FBG3ZFDH3UBRGPORYWTV4I4";
        TimeProvider timeProvider = new SystemTimeProvider();
        CodeGenerator codeGenerator = new DefaultCodeGenerator();
        long bucket = Math.floorDiv(timeProvider.getTime(), 30);
        String code = codeGenerator.generate(secret, bucket);

        DefaultCodeVerifier verifier = new DefaultCodeVerifier(codeGenerator, timeProvider);
        assertTrue(verifier.isValidCode(secret, code));
    }
}
