package com.smarttoll;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class SimulatedCardGateway {

    public record Result(
        boolean approved,
        String reference,
        String reason
    ) {}

    public Result charge(
        String paymentToken,
        BigDecimal amount,
        UUID eventId
    ) {
        if (paymentToken == null ||
            !paymentToken.startsWith("tok_sim_")) {
            return new Result(false, null, "INVALID_TOKEN");
        }

        if (amount == null || amount.signum() <= 0) {
            return new Result(false, null, "INVALID_AMOUNT");
        }

        // Deterministic decline for testing.
        if (paymentToken.startsWith("tok_sim_decline_")) {
            return new Result(false, null, "CARD_DECLINED");
        }

        return new Result(
            true,
            "SIM-" + eventId,
            "APPROVED"
        );
    }
}
