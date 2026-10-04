package dev.ledgerly.wallet.internal.web;

import jakarta.validation.constraints.NotNull;
import java.util.Currency;

record OpenWalletRequest(@NotNull Currency currency) {}
