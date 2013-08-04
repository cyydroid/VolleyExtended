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

import android.os.SystemClock;

import eu.the4thfloor.volleyextended.Cache;
import eu.the4thfloor.volleyextended.VolleyLog;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;


/**
 * Cache implementation that caches files directly onto the hard disk in the specified
 * directory. The default disk usage size is 5MB, but is configurable.
 */
public class DiskBasedCache implements Cache {


  /** Map of the Key, CacheHeader pairs */
  private final Map<String, CacheHeader> mEntries                 = new LinkedHashMap<String, CacheHeader>(16, .75f, true);

  /** Total amount of space currently used by the cache in bytes. */
  private long                           mTotalSize               = 0;

  /** The root directory to use for the cache. */
  private final File                     mRootDirectory;

  /** The maximum size of the cache in bytes. */
  private final int                      mMaxCacheSizeInBytes;

  /** Default maximum disk usage in bytes. */
  private static final int               DEFAULT_DISK_USAGE_BYTES = 5 * 1024 * 1024;

  /** High water mark percentage for the cache */
  private static final float             HYSTERESIS_FACTOR        = 0.9f;

  /** Magic number for current version of cache file format. */
  private static final int               CACHE_MAGIC              = 0x20120504;


  /**
   * Constructs an instance of the DiskBasedCache at the specified directory.
   * 
   * @param rootDirectory
   *          The root directory of the cache.
   * @param maxCacheSizeInBytes
   *          The maximum size of the cache in bytes.
   */
  public DiskBasedCache(final File rootDirectory, final int maxCacheSizeInBytes) {

    this.mRootDirectory = rootDirectory;
    this.mMaxCacheSizeInBytes = maxCacheSizeInBytes;
  }

  /**
   * Constructs an instance of the DiskBasedCache at the specified directory using
   * the default maximum cache size of 5MB.
   * 
   * @param rootDirectory
   *          The root directory of the cache.
   */
  public DiskBasedCache(final File rootDirectory) {

    this(rootDirectory, DEFAULT_DISK_USAGE_BYTES);
  }

  /**
   * Clears the cache. Deletes all cached files from disk.
   */
  @Override
  public synchronized void clear() {

    final File[] files = this.mRootDirectory.listFiles();
    if (files != null) {
      for (final File file : files) {
        file.delete();
      }
    }
    this.mEntries.clear();
    this.mTotalSize = 0;
    VolleyLog.d("Cache cleared.");
  }

  /**
   * Returns the cache entry with the specified key if it exists, null otherwise.
   */
  @Override
  public synchronized Entry get(final String key) {

    final CacheHeader entry = this.mEntries.get(key);
    // if the entry does not exist, return.
    if (entry == null) {
      return null;
    }

    final File file = getFileForKey(key);
    CountingInputStream cis = null;
    try {
      cis = new CountingInputStream(new FileInputStream(file));
      CacheHeader.readHeader(cis); // eat header
      final byte[] data = streamToBytes(cis, (int) (file.length() - cis.bytesRead));
      return entry.toCacheEntry(data);
    }
    catch (final IOException e) {
      VolleyLog.d("%s: %s", file.getAbsolutePath(), e.toString());
      remove(key);
      return null;
    }
    finally {
      if (cis != null) {
        try {
          cis.close();
        }
        catch (final IOException ioe) {
          return null;
        }
      }
    }
  }

  /**
   * Initializes the DiskBasedCache by scanning for all files currently in the
   * specified root directory. Creates the root directory if necessary.
   */
  @Override
  public synchronized void initialize() {

    if (!this.mRootDirectory.exists()) {
      if (!this.mRootDirectory.mkdirs()) {
        VolleyLog.e("Unable to create cache dir %s", this.mRootDirectory.getAbsolutePath());
      }
      return;
    }

    final File[] files = this.mRootDirectory.listFiles();
    if (files == null) {
      return;
    }
    for (final File file : files) {
      FileInputStream fis = null;
      try {
        fis = new FileInputStream(file);
        final CacheHeader entry = CacheHeader.readHeader(fis);
        entry.size = file.length();
        putEntry(entry.key, entry);
      }
      catch (final IOException e) {
        if (file != null) {
          file.delete();
        }
      }
      finally {
        try {
          if (fis != null) {
            fis.close();
          }
        }
        catch (final IOException ignored) {}
      }
    }
  }

  /**
   * Invalidates an entry in the cache.
   * 
   * @param key
   *          Cache key
   * @param fullExpire
   *          True to fully expire the entry, false to soft expire
   */
  @Override
  public synchronized void invalidate(final String key, final boolean fullExpire) {

    final Entry entry = get(key);
    if (entry != null) {
      entry.softTtl = 0;
      if (fullExpire) {
        entry.ttl = 0;
      }
      put(key, entry);
    }

  }

  /**
   * Puts the entry with the specified key into the cache.
   */
  @Override
  public synchronized void put(final String key, final Entry entry) {

    pruneIfNeeded(entry.data.length);
    final File file = getFileForKey(key);
    try {
      final FileOutputStream fos = new FileOutputStream(file);
      final CacheHeader e = new CacheHeader(key, entry);
      e.writeHeader(fos);
      fos.write(entry.data);
      fos.close();
      putEntry(key, e);
      return;
    }
    catch (final IOException e) {}
    final boolean deleted = file.delete();
    if (!deleted) {
      VolleyLog.d("Could not clean up file %s", file.getAbsolutePath());
    }
  }

  /**
   * Removes the specified key from the cache if it exists.
   */
  @Override
  public synchronized void remove(final String key) {

    final boolean deleted = getFileForKey(key).delete();
    removeEntry(key);
    if (!deleted) {
      VolleyLog.d("Could not delete cache entry for key=%s, filename=%s", key, getFilenameForKey(key));
    }
  }

  /**
   * Creates a pseudo-unique filename for the specified cache key.
   * 
   * @param key
   *          The key to generate a file name for.
   * @return A pseudo-unique filename.
   */
  private String getFilenameForKey(final String key) {

    final int firstHalfLength = key.length() / 2;
    String localFilename = String.valueOf(key.substring(0, firstHalfLength).hashCode());
    localFilename += String.valueOf(key.substring(firstHalfLength).hashCode());
    return localFilename;
  }

  /**
   * Returns a file object for the given cache key.
   */
  public File getFileForKey(final String key) {

    return new File(this.mRootDirectory, getFilenameForKey(key));
  }

  /**
   * Prunes the cache to fit the amount of bytes specified.
   * 
   * @param neededSpace
   *          The amount of bytes we are trying to fit into the cache.
   */
  private void pruneIfNeeded(final int neededSpace) {

    if ((this.mTotalSize + neededSpace) < this.mMaxCacheSizeInBytes) {
      return;
    }
    if (VolleyLog.DEBUG) {
      VolleyLog.v("Pruning old cache entries.");
    }

    final long before = this.mTotalSize;
    int prunedFiles = 0;
    final long startTime = SystemClock.elapsedRealtime();

    final Iterator<Map.Entry<String, CacheHeader>> iterator = this.mEntries.entrySet().iterator();
    while (iterator.hasNext()) {
      final Map.Entry<String, CacheHeader> entry = iterator.next();
      final CacheHeader e = entry.getValue();
      final boolean deleted = getFileForKey(e.key).delete();
      if (deleted) {
        this.mTotalSize -= e.size;
      } else {
        VolleyLog.d("Could not delete cache entry for key=%s, filename=%s", e.key, getFilenameForKey(e.key));
      }
      iterator.remove();
      prunedFiles++;

      if ((this.mTotalSize + neededSpace) < (this.mMaxCacheSizeInBytes * HYSTERESIS_FACTOR)) {
        break;
      }
    }

    if (VolleyLog.DEBUG) {
      VolleyLog.v("pruned %d files, %d bytes, %d ms", prunedFiles, (this.mTotalSize - before), SystemClock.elapsedRealtime() - startTime);
    }
  }

  /**
   * Puts the entry with the specified key into the cache.
   * 
   * @param key
   *          The key to identify the entry by.
   * @param entry
   *          The entry to cache.
   */
  private void putEntry(final String key, final CacheHeader entry) {

    if (!this.mEntries.containsKey(key)) {
      this.mTotalSize += entry.size;
    } else {
      final CacheHeader oldEntry = this.mEntries.get(key);
      this.mTotalSize += (entry.size - oldEntry.size);
    }
    this.mEntries.put(key, entry);
  }

  /**
   * Removes the entry identified by 'key' from the cache.
   */
  private void removeEntry(final String key) {

    final CacheHeader entry = this.mEntries.get(key);
    if (entry != null) {
      this.mTotalSize -= entry.size;
      this.mEntries.remove(key);
    }
  }

  /**
   * Reads the contents of an InputStream into a byte[].
   */
  private static byte[] streamToBytes(final InputStream in, final int length) throws IOException {

    final byte[] bytes = new byte[length];
    int count;
    int pos = 0;
    while ((pos < length) && ((count = in.read(bytes, pos, length - pos)) != -1)) {
      pos += count;
    }
    if (pos != length) {
      throw new IOException("Expected " + length + " bytes, read " + pos + " bytes");
    }
    return bytes;
  }


  /**
   * Handles holding onto the cache headers for an entry.
   */
  private static class CacheHeader {


    /**
     * The size of the data identified by this CacheHeader. (This is not
     * serialized to disk.
     */
    public long                size;

    /** The key that identifies the cache entry. */
    public String              key;

    /** ETag for cache coherence. */
    public String              etag;

    /** Date of this response as reported by the server. */
    public long                serverDate;

    /** TTL for this record. */
    public long                ttl;

    /** Soft TTL for this record. */
    public long                softTtl;

    /** Headers from the response resulting in this cache entry. */
    public Map<String, String> responseHeaders;


    private CacheHeader() {

    }

    /**
     * Instantiates a new CacheHeader object
     * 
     * @param key
     *          The key that identifies the cache entry
     * @param entry
     *          The cache entry.
     */
    public CacheHeader(final String key, final Entry entry) {

      this.key = key;
      this.size = entry.data.length;
      this.etag = entry.etag;
      this.serverDate = entry.serverDate;
      this.ttl = entry.ttl;
      this.softTtl = entry.softTtl;
      this.responseHeaders = entry.responseHeaders;
    }

    /**
     * Reads the header off of an InputStream and returns a CacheHeader object.
     * 
     * @param is
     *          The InputStream to read from.
     * @throws IOException
     */
    public static CacheHeader readHeader(final InputStream is) throws IOException {

      final CacheHeader entry = new CacheHeader();
      final int magic = readInt(is);
      if (magic != CACHE_MAGIC) {

        // don't bother deleting, it'll get pruned eventually
        throw new IOException();
      }
      entry.key = readString(is);
      entry.etag = readString(is);
      if (entry.etag.equals("")) {
        entry.etag = null;
      }
      entry.serverDate = readLong(is);
      entry.ttl = readLong(is);
      entry.softTtl = readLong(is);
      entry.responseHeaders = readStringStringMap(is);
      return entry;
    }

    /**
     * Creates a cache entry for the specified data.
     */
    public Entry toCacheEntry(final byte[] data) {

      final Entry e = new Entry();
      e.data = data;
      e.etag = this.etag;
      e.serverDate = this.serverDate;
      e.ttl = this.ttl;
      e.softTtl = this.softTtl;
      e.responseHeaders = this.responseHeaders;
      return e;
    }

    /**
     * Writes the contents of this CacheHeader to the specified OutputStream.
     */
    public boolean writeHeader(final OutputStream os) {

      try {
        writeInt(os, CACHE_MAGIC);
        writeString(os, this.key);
        writeString(os, this.etag == null ? "" : this.etag);
        writeLong(os, this.serverDate);
        writeLong(os, this.ttl);
        writeLong(os, this.softTtl);
        writeStringStringMap(this.responseHeaders, os);
        os.flush();
        return true;
      }
      catch (final IOException e) {
        VolleyLog.d("%s", e.toString());
        return false;
      }
    }
  }

  private static class CountingInputStream extends FilterInputStream {


    private int bytesRead = 0;


    private CountingInputStream(final InputStream in) {

      super(in);
    }

    @Override
    public int read() throws IOException {

      final int result = super.read();
      if (result != -1) {
        this.bytesRead++;
      }
      return result;
    }

    @Override
    public int read(final byte[] buffer, final int offset, final int count) throws IOException {

      final int result = super.read(buffer, offset, count);
      if (result != -1) {
        this.bytesRead += result;
      }
      return result;
    }
  }


  /*
   * Homebrewed simple serialization system used for reading and writing cache
   * headers on disk. Once upon a time, this used the standard Java
   * Object{Input,Output}Stream, but the default implementation relies heavily
   * on reflection (even for standard types) and generates a ton of garbage.
   */

  /**
   * Simple wrapper around {@link InputStream#read()} that throws EOFException
   * instead of returning -1.
   */
  private static int read(final InputStream is) throws IOException {

    final int b = is.read();
    if (b == -1) {
      throw new EOFException();
    }
    return b;
  }

  static void writeInt(final OutputStream os, final int n) throws IOException {

    os.write((n >> 0) & 0xff);
    os.write((n >> 8) & 0xff);
    os.write((n >> 16) & 0xff);
    os.write((n >> 24) & 0xff);
  }

  static int readInt(final InputStream is) throws IOException {

    int n = 0;
    n |= (read(is) << 0);
    n |= (read(is) << 8);
    n |= (read(is) << 16);
    n |= (read(is) << 24);
    return n;
  }

  static void writeLong(final OutputStream os, final long n) throws IOException {

    os.write((byte) (n >>> 0));
    os.write((byte) (n >>> 8));
    os.write((byte) (n >>> 16));
    os.write((byte) (n >>> 24));
    os.write((byte) (n >>> 32));
    os.write((byte) (n >>> 40));
    os.write((byte) (n >>> 48));
    os.write((byte) (n >>> 56));
  }

  static long readLong(final InputStream is) throws IOException {

    long n = 0;
    n |= ((read(is) & 0xFFL) << 0);
    n |= ((read(is) & 0xFFL) << 8);
    n |= ((read(is) & 0xFFL) << 16);
    n |= ((read(is) & 0xFFL) << 24);
    n |= ((read(is) & 0xFFL) << 32);
    n |= ((read(is) & 0xFFL) << 40);
    n |= ((read(is) & 0xFFL) << 48);
    n |= ((read(is) & 0xFFL) << 56);
    return n;
  }

  static void writeString(final OutputStream os, final String s) throws IOException {

    final byte[] b = s.getBytes("UTF-8");
    writeLong(os, b.length);
    os.write(b, 0, b.length);
  }

  static String readString(final InputStream is) throws IOException {

    final int n = (int) readLong(is);
    final byte[] b = streamToBytes(is, n);
    return new String(b, "UTF-8");
  }

  static void writeStringStringMap(final Map<String, String> map, final OutputStream os) throws IOException {

    if (map != null) {
      writeInt(os, map.size());
      for (final Map.Entry<String, String> entry : map.entrySet()) {
        writeString(os, entry.getKey());
        writeString(os, entry.getValue());
      }
    } else {
      writeInt(os, 0);
    }
  }

  static Map<String, String> readStringStringMap(final InputStream is) throws IOException {

    final int size = readInt(is);
    final Map<String, String> result = (size == 0) ? Collections.<String, String> emptyMap() : new HashMap<String, String>(size);
    for (int i = 0; i < size; i++) {
      final String key = readString(is).intern();
      final String value = readString(is).intern();
      result.put(key, value);
    }
    return result;
  }
}
