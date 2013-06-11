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

package eu.the4thfloor.volleyextended;

/**
 * Encapsulates a parsed response for delivery.
 * 
 * @param <T>
 *          Parsed type of this response
 */
public class Response<T> {


  /** Callback interface for delivering parsed responses. */
  public interface Listener<T> {


    /** Called when a response is received. */
    public void onResponse(T response, boolean hasChanged);
  }

  /** Callback interface for delivering error responses. */
  public interface ErrorListener<T> {


    /**
     * Callback method that an error has been occurred with the provided error code and optional user-readable message.
     * Optional a cached response if there on in the cache.
     */
    public void onErrorResponse(VolleyError error, T cachedResponse);
  }


  /** Returns a successful response containing the parsed result. */
  public static <T> Response<T> success(final T result, final Cache.Entry cacheEntry) {

    return new Response<T>(result, cacheEntry);
  }

  /**
   * Returns a failed response containing the given error code and an optional
   * localized message displayed to the user.
   */
  public static <T> Response<T> error(final VolleyError error) {

    return new Response<T>(error);
  }


  /** Parsed response, or null in the case of error. */
  public final T           result;

  /** Cache metadata for this response, or null in the case of error. */
  public final Cache.Entry cacheEntry;

  /** Detailed error information if <code>errorCode != OK</code>. */
  public final VolleyError error;

  /** True if this response has changed since the last request. */
  public boolean           hasChanged   = true;

  /** True if this response was a soft-expired one and a second one MAY be coming. */
  public boolean           intermediate = false;


  /**
   * Returns whether this response is considered successful.
   */
  public boolean isSuccess() {

    return this.error == null;
  }

  private Response(final T result, final Cache.Entry cacheEntry) {

    this.result = result;
    this.cacheEntry = cacheEntry;
    this.error = null;
  }

  private Response(final VolleyError error) {

    this.result = null;
    this.cacheEntry = null;
    this.error = error;
  }
}
