package com.dankhole.airlockandroid;

final class ApprovalCodePolicy {
    static final int CODE_LENGTH = 4;
    static final int REQUEST_CODE_MIN = 0;
    static final int REQUEST_CODE_COUNT = 10_000;
    static final int APPROVAL_TABLE_LENGTH = REQUEST_CODE_COUNT * CODE_LENGTH;

    private static final int APPROVAL_MODULUS = 10_000;
    private static final int PRODUCT_DIGITS_TO_DROP = 100;

    private ApprovalCodePolicy() {
    }

    static int approvedMinutes(
            boolean codeIsPending,
            long expiresAtMs,
            int storedRequestedMinutes,
            long nowMs
    ) {
        return codeIsPending && expiresAtMs > nowMs && storedRequestedMinutes > 0
                ? storedRequestedMinutes
                : Preferences.APPROVAL_REDEMPTION_INVALID;
    }

    static long unlockUntilMs(long nowMs, int approvedMinutes) {
        return nowMs + Math.max(1, approvedMinutes) * 60_000L;
    }

    static boolean isValidMasterPin(String pin) {
        return isFourDigits(pin) && !"0000".equals(pin);
    }

    static boolean isValidRequestCode(String requestCode) {
        return isFourDigits(requestCode);
    }

    static String approvalCodeForRequestAndPin(String requestCode, String masterPin) {
        if (!isValidRequestCode(requestCode) || !isValidMasterPin(masterPin)) {
            return "";
        }
        long product = (long) Integer.parseInt(requestCode) * Integer.parseInt(masterPin);
        int approval = (int) ((product / PRODUCT_DIGITS_TO_DROP) % APPROVAL_MODULUS);
        return fourDigitCode(approval);
    }

    static String approvalTableForPin(String masterPin) {
        if (!isValidMasterPin(masterPin)) {
            return "";
        }
        int pinValue = Integer.parseInt(masterPin);
        StringBuilder table = new StringBuilder(APPROVAL_TABLE_LENGTH);
        for (int request = REQUEST_CODE_MIN;
                request < REQUEST_CODE_MIN + REQUEST_CODE_COUNT;
                request++) {
            long product = (long) request * pinValue;
            int approval = (int) ((product / PRODUCT_DIGITS_TO_DROP) % APPROVAL_MODULUS);
            appendFourDigitCode(table, approval);
        }
        return table.toString();
    }

    static boolean isValidApprovalTable(String table) {
        return table != null && table.length() == APPROVAL_TABLE_LENGTH;
    }

    static String approvalCodeFromTable(String table, String requestCode) {
        if (!isValidApprovalTable(table) || !isValidRequestCode(requestCode)) {
            return "";
        }
        int request = Integer.parseInt(requestCode);
        int start = (request - REQUEST_CODE_MIN) * CODE_LENGTH;
        return table.substring(start, start + CODE_LENGTH);
    }

    static String testingApprovalCodeForRequest(String requestCode) {
        if (!isValidRequestCode(requestCode)) {
            return "";
        }
        StringBuilder builder = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < requestCode.length(); i++) {
            int digit = requestCode.charAt(i) - '0';
            builder.append((digit + 5) % 10);
        }
        return builder.toString();
    }

    private static boolean isFourDigits(String value) {
        if (value == null || value.length() != CODE_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char digit = value.charAt(i);
            if (digit < '0' || digit > '9') {
                return false;
            }
        }
        return true;
    }

    private static String fourDigitCode(int value) {
        StringBuilder builder = new StringBuilder(CODE_LENGTH);
        appendFourDigitCode(builder, value);
        return builder.toString();
    }

    private static void appendFourDigitCode(StringBuilder builder, int value) {
        builder.append((char) ('0' + (value / 1_000) % 10));
        builder.append((char) ('0' + (value / 100) % 10));
        builder.append((char) ('0' + (value / 10) % 10));
        builder.append((char) ('0' + value % 10));
    }
}
