/*
 * Copyright (C) 2011 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.volleyextended.toolbox;

import com.android.volleyextended.Request;
import com.android.volleyextended.Response;
import com.android.volleyextended.VolleyError;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


/**
 * A Future that represents a Volley request.
 * Used by providing as your response and error listeners. For example:
 * 
 * <pre>
 * RequestFuture&lt;JSONObject&gt; future = RequestFuture.newFuture();
 * MyRequest request = new MyRequest(URL, future, future);
 * 
 * // If you want to be able to cancel the request:
 * future.setRequest(requestQueue.add(request));
 * 
 * // Otherwise:
 * requestQueue.add(request);
 * 
 * try {
 *   JSONObject response = future.get();
 *   // do something with response
 * }
 * catch (InterruptedException e) {
 *   // handle the error
 * }
 * catch (ExecutionException e) {
 *   // handle the error
 * }
 * </pre>
 * 
 * @param <T>
 *          The type of parsed response this future expects.
 */
public class RequestFuture<T> implements Future<T>, Response.Listener<T>, Response.ErrorListener<T> {


  private Request<?>  mRequest;
  private boolean     mResultReceived = false;
  private T           mResult;
  private VolleyError mException;


  public static <E> RequestFuture<E> newFuture() {

    return new RequestFuture<E>();
  }

  private RequestFuture() {

  }

  public void setRequest(final Request<?> request) {

    this.mRequest = request;
  }

  @Override
  public synchronized boolean cancel(final boolean mayInterruptIfRunning) {

    if (this.mRequest == null) {
      return false;
    }

    if (!isDone()) {
      this.mRequest.cancel();
      return true;
    } else {
      return false;
    }
  }

  @Override
  public T get() throws InterruptedException, ExecutionException {

    try {
      return doGet(null);
    }
    catch (final TimeoutException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  public T get(final long timeout, final TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {

    return doGet(TimeUnit.MILLISECONDS.convert(timeout, unit));
  }

  private synchronized T doGet(final Long timeoutMs) throws InterruptedException, ExecutionException, TimeoutException {

    if (this.mException != null) {
      throw new ExecutionException(this.mException);
    }

    if (this.mResultReceived) {
      return this.mResult;
    }

    if (timeoutMs == null) {
      wait(0);
    } else if (timeoutMs > 0) {
      wait(timeoutMs);
    }

    if (this.mException != null) {
      throw new ExecutionException(this.mException);
    }

    if (!this.mResultReceived) {
      throw new TimeoutException();
    }

    return this.mResult;
  }

  @Override
  public boolean isCancelled() {

    if (this.mRequest == null) {
      return false;
    }
    return this.mRequest.isCanceled();
  }

  @Override
  public synchronized boolean isDone() {

    return this.mResultReceived || (this.mException != null) || isCancelled();
  }

  @Override
  public synchronized void onResponse(final T response, final boolean hasChanged) {

    this.mResultReceived = true;
    this.mResult = response;
    notifyAll();
  }

  @Override
  public synchronized void onErrorResponse(final VolleyError error, final T cachedResponse) {

    this.mException = error;
    notifyAll();
  }
}
