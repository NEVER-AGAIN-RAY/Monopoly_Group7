package com.monopoly.fx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

import static com.monopoly.fx.FxJson.jsonInt;
import static com.monopoly.fx.FxJson.jsonString;

/** Chooses rent payment cards while preserving the current UI recommendation policy. */
final class RentPaymentAdvisor {

    private RentPaymentAdvisor() {
    }

    static List<String> recommendedPaymentIds(JsonObject self, int amountDueM) {
        if (self == null || amountDueM <= 0) {
            return List.of();
        }
        List<PaymentOption> options = new ArrayList<>();
        collectPaymentOptions(self.getAsJsonArray("bankCards"), "BANK", options);
        collectPaymentOptions(self.getAsJsonArray("propertyZoneCards"), "PROPERTY", options);
        return bestPaymentCardIds(options, amountDueM);
    }

    private static void collectPaymentOptions(JsonArray cards, String zoneKey, List<PaymentOption> out) {
        if (cards == null) {
            return;
        }
        for (JsonElement el : cards) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject card = el.getAsJsonObject();
            String id = jsonString(card, "id", "");
            int value = jsonInt(card, "valueM", 0);
            if (!id.isBlank() && value > 0) {
                out.add(new PaymentOption(id, value, zoneKey));
            }
        }
    }

    static List<String> bestPaymentCardIds(List<PaymentOption> cards, int amountDue) {
        int due = Math.max(0, amountDue);
        if (due <= 0 || cards == null || cards.isEmpty()) {
            return List.of();
        }
        List<PaymentOption> positive = cards.stream()
                .filter(option -> option.value() > 0)
                .toList();
        if (positive.isEmpty()) {
            return List.of();
        }
        List<PaymentOption> bankOnly = positive.stream()
                .filter(option -> !"PROPERTY".equals(option.zoneKey()))
                .toList();
        int bankTotal = bankOnly.stream().mapToInt(PaymentOption::value).sum();
        List<PaymentOption> eligible = bankTotal >= due ? bankOnly : positive;
        int total = eligible.stream().mapToInt(PaymentOption::value).sum();
        if (total < due) {
            return eligible.stream().map(PaymentOption::id).toList();
        }

        List<PaymentChoice> dp = new ArrayList<>();
        for (int i = 0; i <= total; i++) {
            dp.add(null);
        }
        dp.set(0, new PaymentChoice(List.of(), 0, 0, 0, 0));
        for (PaymentOption option : eligible) {
            for (int sum = total - option.value(); sum >= 0; sum--) {
                PaymentChoice prev = dp.get(sum);
                if (prev == null) {
                    continue;
                }
                int nextSum = sum + option.value();
                PaymentChoice next = prev.with(option);
                PaymentChoice current = dp.get(nextSum);
                if (current == null || comparePaymentChoice(nextSum, next, nextSum, current) < 0) {
                    dp.set(nextSum, next);
                }
            }
        }

        int bestSum = -1;
        PaymentChoice best = null;
        for (int sum = due; sum <= total; sum++) {
            PaymentChoice candidate = dp.get(sum);
            if (candidate == null) {
                continue;
            }
            if (best == null || comparePaymentChoice(sum, candidate, bestSum, best) < 0) {
                bestSum = sum;
                best = candidate;
            }
        }
        return best == null ? List.of() : best.ids();
    }

    private static int comparePaymentChoice(int amountA, PaymentChoice a, int amountB, PaymentChoice b) {
        int amount = Integer.compare(amountA, amountB);
        if (amount != 0) {
            return amount;
        }
        int propertyCount = Integer.compare(a.propertyCount(), b.propertyCount());
        if (propertyCount != 0) {
            return propertyCount;
        }
        int propertyValue = Integer.compare(a.propertyValue(), b.propertyValue());
        if (propertyValue != 0) {
            return propertyValue;
        }
        int cardCount = Integer.compare(a.cardCount(), b.cardCount());
        if (cardCount != 0) {
            return cardCount;
        }
        return Integer.compare(a.bankValue(), b.bankValue());
    }

    record PaymentOption(String id, int value, String zoneKey) {
    }

    private record PaymentChoice(List<String> ids, int cardCount, int bankValue, int propertyCount, int propertyValue) {
        PaymentChoice with(PaymentOption option) {
            List<String> nextIds = new ArrayList<>(ids);
            nextIds.add(option.id());
            boolean property = "PROPERTY".equals(option.zoneKey());
            return new PaymentChoice(
                    List.copyOf(nextIds),
                    cardCount + 1,
                    bankValue + (property ? 0 : option.value()),
                    propertyCount + (property ? 1 : 0),
                    propertyValue + (property ? option.value() : 0));
        }
    }
}
