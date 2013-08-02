/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.the4thfloor.volleyextended.toolbox;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerFuture;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import eu.the4thfloor.volleyextended.AuthFailureError;


/**
 * An Authenticator that uses {@link AccountManager} to get auth
 * tokens of a specified type for a specified account.
 */
public class AndroidAuthenticator implements Authenticator {


  private final Context mContext;
  private final Account mAccount;
  private final String  mAuthTokenType;
  private final boolean mNotifyAuthFailure;


  /**
   * Creates a new authenticator.
   * 
   * @param context
   *          Context for accessing AccountManager
   * @param account
   *          Account to authenticate as
   * @param authTokenType
   *          Auth token type passed to AccountManager
   */
  public AndroidAuthenticator(final Context context, final Account account, final String authTokenType) {

    this(context, account, authTokenType, false);
  }

  /**
   * Creates a new authenticator.
   * 
   * @param context
   *          Context for accessing AccountManager
   * @param account
   *          Account to authenticate as
   * @param authTokenType
   *          Auth token type passed to AccountManager
   * @param notifyAuthFailure
   *          Whether to raise a notification upon auth failure
   */
  public AndroidAuthenticator(final Context context, final Account account, final String authTokenType, final boolean notifyAuthFailure) {

    this.mContext = context;
    this.mAccount = account;
    this.mAuthTokenType = authTokenType;
    this.mNotifyAuthFailure = notifyAuthFailure;
  }

  /**
   * Returns the Account being used by this authenticator.
   */
  public Account getAccount() {

    return this.mAccount;
  }

  @Override
  public String getAuthToken() throws AuthFailureError {

    final AccountManager accountManager = AccountManager.get(this.mContext);
    final AccountManagerFuture<Bundle> future = accountManager.getAuthToken(this.mAccount, this.mAuthTokenType, this.mNotifyAuthFailure, null, null);
    Bundle result;
    try {
      result = future.getResult();
    }
    catch (final Exception e) {
      throw new AuthFailureError("Error while retrieving auth token", e);
    }
    String authToken = null;
    if (future.isDone() && !future.isCancelled()) {
      if (result.containsKey(AccountManager.KEY_INTENT)) {
        final Intent intent = result.getParcelable(AccountManager.KEY_INTENT);
        throw new AuthFailureError(intent);
      }
      authToken = result.getString(AccountManager.KEY_AUTHTOKEN);
    }
    if (authToken == null) {
      throw new AuthFailureError("Got null auth token for type: " + this.mAuthTokenType);
    }

    return authToken;
  }

  @Override
  public void invalidateAuthToken(final String authToken) {

    AccountManager.get(this.mContext).invalidateAuthToken(this.mAccount.type, authToken);
  }
}
