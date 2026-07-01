package com.rfidw.app.auth;

/**
 * Lokální seznam operátorů. Postupně doplňovat další záznamy.
 */
public final class UserAccounts {

    public static final class Account {
        public final String userId;
        public final String password;

        Account(String userId, String password) {
            this.userId = userId;
            this.password = password;
        }
    }

    private static final Account[] ACCOUNTS = {
            new Account("1", "0000"),
    };

    private UserAccounts() {}

    public static boolean verify(String userId, String password) {
        if (userId == null || password == null) return false;
        String id = userId.trim();
        for (Account account : ACCOUNTS) {
            if (account.userId.equals(id) && account.password.equals(password)) {
                return true;
            }
        }
        return false;
    }
}
