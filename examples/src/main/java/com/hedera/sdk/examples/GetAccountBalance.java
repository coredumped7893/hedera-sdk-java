package com.hedera.sdk.examples;

import com.hedera.sdk.HederaException;
import com.hedera.sdk.account.AccountBalanceQuery;

@SuppressWarnings("Duplicates")
public final class GetAccountBalance {
    public static void main(String[] args) throws HederaException {
        var operatorId = ExampleHelper.getOperatorId();
        var client = ExampleHelper.createHederaClient();

        var query = new AccountBalanceQuery(client).setAccountId(operatorId);

        var balance = query.execute();

        System.out.println("balance = " + balance);
    }
}
