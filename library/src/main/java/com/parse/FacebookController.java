/*
 * Copyright (c) 2015-present, Parse, LLC.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */
package com.parse;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.support.v4.app.Fragment;

import com.facebook.AccessToken;
import com.facebook.CallbackManager;
import com.facebook.FacebookCallback;
import com.facebook.FacebookException;
import com.facebook.FacebookSdk;
import com.facebook.login.LoginManager;
import com.facebook.login.LoginResult;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.SimpleTimeZone;

import bolts.Task;

/** package */ class FacebookController {

  /**
   * Precise date format required for auth expiration data.
   */
  private static final DateFormat PRECISE_DATE_FORMAT =
      new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
  static {
    PRECISE_DATE_FORMAT.setTimeZone(new SimpleTimeZone(0, "GMT"));
  }

  // Used as default activityCode. From FacebookSdk.java.
  public static final int DEFAULT_AUTH_ACTIVITY_CODE = 0xface;

  private static final String KEY_USER_ID = "id";
  private static final String KEY_ACCESS_TOKEN = "access_token";
  private static final String KEY_EXPIRATION_DATE = "expiration_date";

  // Mirrors com.facebook.internal.LoginAuthorizationType.java
  public enum LoginAuthorizationType {
    READ, PUBLISH
  }

  private final FacebookSdkDelegate facebookSdkDelegate;

  private CallbackManager callbackManager;

  /* package */ FacebookController(FacebookSdkDelegate facebookSdkDelegate) {
    this.facebookSdkDelegate = facebookSdkDelegate;
  }

  public FacebookController() {
    this(new FacebookSdkDelegateImpl());
  }

  public void initialize(Context context, int callbackRequestCodeOffset) {
    facebookSdkDelegate.initialize(context, callbackRequestCodeOffset);
  }

  public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
    boolean ret = false;
    if (callbackManager != null) {
      ret = callbackManager.onActivityResult(requestCode, resultCode, data);
      // Release callbackManager so our login callback doesn't get called again
      callbackManager = null;
    }
    return ret;
  }

  public Task<Map<String, String>> authenticateAsync(
      Activity activity,
      Fragment fragment,
      LoginAuthorizationType authorizationType,
      Collection<String> permissions) {
    if (callbackManager != null) {
      // This should never happen since FB auth takes over UI and starts an Activity
      return Task.forError(
          new RuntimeException("Unable to authenticate when another authentication is in process"));
    }

    final Task<Map<String, String>>.TaskCompletionSource tcs = Task.create();
    LoginManager manager = facebookSdkDelegate.getLoginManager();

    callbackManager = facebookSdkDelegate.createCallbackManager();
    manager.registerCallback(callbackManager, new FacebookCallback<LoginResult>() {
      @Override
      public void onSuccess(LoginResult loginResult) {
        AccessToken accessToken = loginResult.getAccessToken();
        Map<String, String> authData = getAuthData(accessToken);
        tcs.trySetResult(authData);
      }

      @Override
      public void onCancel() {
        tcs.trySetCancelled();
      }

      @Override
      public void onError(FacebookException e) {
        tcs.trySetError(e);
      }
    });

    if (LoginAuthorizationType.PUBLISH.equals(authorizationType)) {
      if (fragment != null) {
        manager.logInWithPublishPermissions(fragment, permissions);
      } else {
        manager.logInWithPublishPermissions(activity, permissions);
      }
    } else {
      if (fragment != null) {
        manager.logInWithReadPermissions(fragment, permissions);
      } else {
        manager.logInWithReadPermissions(activity, permissions);
      }
    }

    return tcs.getTask();
  }

  public Map<String, String> getAuthData(AccessToken accessToken) {
    Map<String, String> authData = new HashMap<>();
    authData.put(KEY_USER_ID, accessToken.getUserId());
    authData.put(KEY_ACCESS_TOKEN, accessToken.getToken());
    authData.put(KEY_EXPIRATION_DATE, PRECISE_DATE_FORMAT.format(accessToken.getExpires()));
    return authData;
  }

  public void setAuthData(Map<String, String> authData)
      throws java.text.ParseException {
    if (authData == null) {
      facebookSdkDelegate.getLoginManager().logOut();
      return;
    }

    String token = authData.get(KEY_ACCESS_TOKEN);
    String userId = authData.get(KEY_USER_ID);

    AccessToken currentAccessToken = facebookSdkDelegate.getCurrentAccessToken();
    if (currentAccessToken != null) {
      String currToken = currentAccessToken.getToken();
      String currUserId = currentAccessToken.getUserId();
      if (currToken != null && currToken.equals(token)
          && currUserId != null && currUserId.equals(userId)) {
        // Don't reset the current token if it's the same. If we reset it every time we'd lose
        // permissions, source, lastRefreshTime, etc.
        return;
      }
    }
    AccessToken accessToken = new AccessToken(
        token,
        facebookSdkDelegate.getApplicationId(),
        userId,
        null,
        null,
        null,
        PRECISE_DATE_FORMAT.parse(authData.get(KEY_EXPIRATION_DATE)),
        null);
    facebookSdkDelegate.setCurrentAccessToken(accessToken);
  }

  /* package */ interface FacebookSdkDelegate {
    void initialize(Context context, int callbackRequestCodeOffset);
    String getApplicationId();
    AccessToken getCurrentAccessToken();
    void setCurrentAccessToken(AccessToken token);
    CallbackManager createCallbackManager();
    LoginManager getLoginManager();
  }

  private static class FacebookSdkDelegateImpl implements FacebookSdkDelegate {
    @Override
    public void initialize(Context context, int callbackRequestCodeOffset) {
      FacebookSdk.sdkInitialize(context, callbackRequestCodeOffset);
    }

    @Override
    public String getApplicationId() {
      return FacebookSdk.getApplicationId();
    }

    @Override
    public AccessToken getCurrentAccessToken() {
      return AccessToken.getCurrentAccessToken();
    }

    @Override
    public void setCurrentAccessToken(AccessToken token) {
      AccessToken.setCurrentAccessToken(token);
    }

    @Override
    public CallbackManager createCallbackManager() {
      return CallbackManager.Factory.create();
    }

    @Override
    public LoginManager getLoginManager() {
      return LoginManager.getInstance();
    }
  }
}
