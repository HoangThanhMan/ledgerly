package dev.ledgerly.ledger;

/** System accounts seeded by migration {@code V2__system_accounts.sql}, identified by their unique code. */
public enum SystemAccount {
    /** Source of internal deposits for development and testing. */
    FUNDING("system:funding"),
    /** User money held at the bank. */
    BANK_SETTLEMENT("system:bank-settlement"),
    /** Withdrawals held until the bank pays out. */
    WITHDRAWAL_SUSPENSE("system:withdrawal-suspense");

    private final String code;

    SystemAccount(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
