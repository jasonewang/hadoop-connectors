/**
 * Copyright 2013 Google Inc. All Rights Reserved.
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.hadoop.gcsio;

import static org.junit.Assert.assertNull;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.google.api.client.util.Clock;
import com.google.cloud.hadoop.gcsio.testing.InMemoryGoogleCloudStorage;
import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
public class PerformanceCachingGoogleCloudStorageTest {

  /** Sample empty metdata. */
  private static final Map<String, byte[]> TEST_METADATA =
      ImmutableMap.of("test_key", new byte[] {2});

  private static final CreateBucketOptions CREATE_BUCKET_OPTIONS =
      new CreateBucketOptions("test_location", "test_storage_class");

  private static final CreateObjectOptions CREATE_OBJECT_OPTIONS =
      new CreateObjectOptions(true, "test_content_type", TEST_METADATA, true);

  /** Sample bucket name. */
  private static final String TEST_BUCKET_A = "test_bucket_a";

  /** Sample bucket name. */
  private static final String TEST_BUCKET_B = "test_bucket_b";

  /** Sample object name. */
  private static final String TEST_OBJECT_NAME_A = "test_object_name_a";

  /** Sample object name. */
  private static final String TEST_OBJECT_NAME_B = "test_object_name_b";

  /** Sample object name. */
  private static final String TEST_OBJECT_NAME_C = "test_object_name_c";

  /** Sample bucket item info. */
  private static final GoogleCloudStorageItemInfo TEST_ITEM_A = createBucketItemInfo(TEST_BUCKET_A);

  /** Sample bucket item info. */
  private static final GoogleCloudStorageItemInfo TEST_ITEM_B = createBucketItemInfo(TEST_BUCKET_B);

  /** Sample item info. */
  private static final GoogleCloudStorageItemInfo TEST_ITEM_A_A =
      createObjectItemInfo(TEST_BUCKET_A, TEST_OBJECT_NAME_A);

  /** Sample item info. */
  private static final GoogleCloudStorageItemInfo TEST_ITEM_A_B =
      createObjectItemInfo(TEST_BUCKET_A, TEST_OBJECT_NAME_B);

  /** Sample item info. */
  private static final GoogleCloudStorageItemInfo TEST_ITEM_B_A =
      createObjectItemInfo(TEST_BUCKET_B, TEST_OBJECT_NAME_A);

  /** Sample item info. */
  private static final GoogleCloudStorageItemInfo TEST_ITEM_B_B =
      createObjectItemInfo(TEST_BUCKET_B, TEST_OBJECT_NAME_B);

  /** Sample item info. */
  private static final GoogleCloudStorageItemInfo TEST_ITEM_B_C =
      createObjectItemInfo(TEST_BUCKET_B, TEST_OBJECT_NAME_C);

  /** Clock implementation for testing the GCS delegate. */
  private TestClock clock;
  /** CachingGoogleCloudStorage instance being tested. */
  private PerformanceCachingGoogleCloudStorage gcs;
  /** Cache implementation to back the GCS instance being tested. */
  private Cache<StorageResourceId, GoogleCloudStorageItemInfo> cache;
  /** GoogleCloudStorage implementation to back the GCS instance being tested. */
  private GoogleCloudStorage gcsDelegate;

  @Before
  public void setUp() throws IOException {
    // Setup mocks.
    MockitoAnnotations.initMocks(this);

    clock = new TestClock();
    cache =
        CacheBuilder.newBuilder()
            .expireAfterWrite(10, TimeUnit.NANOSECONDS)
            .ticker(new TestTicker())
            .build();
    GoogleCloudStorage gcsImpl =
        new InMemoryGoogleCloudStorage(GoogleCloudStorageOptions.newBuilder().build(), clock);
    gcsDelegate = spy(gcsImpl);
    gcs = new PerformanceCachingGoogleCloudStorage(gcsDelegate, cache);

    // Prepare the delegate.
    gcsDelegate.create(TEST_BUCKET_A, CREATE_BUCKET_OPTIONS);
    gcsDelegate.create(TEST_BUCKET_B, CREATE_BUCKET_OPTIONS);
    gcsDelegate.createEmptyObject(TEST_ITEM_A_A.getResourceId(), CREATE_OBJECT_OPTIONS);
    gcsDelegate.createEmptyObject(TEST_ITEM_A_B.getResourceId(), CREATE_OBJECT_OPTIONS);
    gcsDelegate.createEmptyObject(TEST_ITEM_B_A.getResourceId(), CREATE_OBJECT_OPTIONS);
    gcsDelegate.createEmptyObject(TEST_ITEM_B_B.getResourceId(), CREATE_OBJECT_OPTIONS);
    gcsDelegate.createEmptyObject(TEST_ITEM_B_C.getResourceId(), CREATE_OBJECT_OPTIONS);
  }

  @Test
  public void testDeleteBuckets() throws IOException {
    List<String> buckets = Lists.newArrayList(TEST_BUCKET_A);

    // Prepare the cache.
    cache.put(TEST_ITEM_A_A.getResourceId(), TEST_ITEM_A_A); // Deleted.
    cache.put(TEST_ITEM_B_A.getResourceId(), TEST_ITEM_B_A); // Not deleted.

    gcs.deleteBuckets(buckets);

    // Verify the delegate call.
    verify(gcsDelegate).deleteBuckets(eq(buckets));
    // Verify the cache was updated.
    assertNull(cache.getIfPresent(TEST_ITEM_A_A.getResourceId()));
    assertEquals(cache.getIfPresent(TEST_ITEM_B_A.getResourceId()), TEST_ITEM_B_A);
  }

  @Test
  public void testDeleteObjects() throws IOException {
    List<StorageResourceId> ids =
        Lists.newArrayList(TEST_ITEM_A_A.getResourceId(), TEST_ITEM_B_A.getResourceId());

    // Prepare the cache.
    cache.put(TEST_ITEM_A_A.getResourceId(), TEST_ITEM_A_A); // Deleted.
    cache.put(TEST_ITEM_B_A.getResourceId(), TEST_ITEM_B_A); // Deleted.
    cache.put(TEST_ITEM_B_B.getResourceId(), TEST_ITEM_B_B); // Not deleted.

    gcs.deleteObjects(ids);

    // Verify the delegate call.
    verify(gcsDelegate).deleteObjects(eq(ids));
    // Verify the cache was updated.
    assertNull(cache.getIfPresent(TEST_ITEM_A_A.getResourceId()));
    assertNull(cache.getIfPresent(TEST_ITEM_B_A.getResourceId()));
    assertEquals(cache.getIfPresent(TEST_ITEM_B_B.getResourceId()), TEST_ITEM_B_B);
  }

  @Test
  public void testListBucketInfo() throws IOException {
    List<GoogleCloudStorageItemInfo> expected = Lists.newArrayList(TEST_ITEM_A, TEST_ITEM_B);

    List<GoogleCloudStorageItemInfo> result = gcs.listBucketInfo();

    // Verify the delegate call.
    verify(gcsDelegate).listBucketInfo();
    assertContainsInAnyOrder(result, expected);
    // Verify the cache was updated.
    assertEquals(cache.getIfPresent(TEST_ITEM_A.getResourceId()), TEST_ITEM_A);
    assertEquals(cache.getIfPresent(TEST_ITEM_B.getResourceId()), TEST_ITEM_B);
  }

  @Test
  public void testListObjectInfo() throws IOException {
    List<GoogleCloudStorageItemInfo> expected = Lists.newArrayList(TEST_ITEM_A_A, TEST_ITEM_A_B);

    List<GoogleCloudStorageItemInfo> result = gcs.listObjectInfo(TEST_BUCKET_A, null, null);

    // Verify the delegate call.
    verify(gcsDelegate).listObjectInfo(eq(TEST_BUCKET_A), eq(null), eq(null));
    assertContainsInAnyOrder(result, expected);
    // Verify the cache was updated.
    assertEquals(cache.getIfPresent(TEST_ITEM_A_A.getResourceId()), TEST_ITEM_A_A);
    assertEquals(cache.getIfPresent(TEST_ITEM_A_B.getResourceId()), TEST_ITEM_A_B);
  }

  @Test
  public void testListObjectInfoAlt() throws IOException {
    List<GoogleCloudStorageItemInfo> expected =
        Lists.newArrayList(TEST_ITEM_B_A, TEST_ITEM_B_B, TEST_ITEM_B_C);

    List<GoogleCloudStorageItemInfo> result = gcs.listObjectInfo(TEST_BUCKET_B, null, null, 0L);

    // Verify the delegate call.
    verify(gcsDelegate).listObjectInfo(eq(TEST_BUCKET_B), eq(null), eq(null), eq(0L));
    assertContainsInAnyOrder(result, expected);
    // Verify the cache was updated.
    assertEquals(cache.getIfPresent(TEST_ITEM_B_A.getResourceId()), TEST_ITEM_B_A);
    assertEquals(cache.getIfPresent(TEST_ITEM_B_B.getResourceId()), TEST_ITEM_B_B);
    assertEquals(cache.getIfPresent(TEST_ITEM_B_C.getResourceId()), TEST_ITEM_B_C);
  }

  @Test
  public void testGetItemInfo() throws IOException {
    // Prepare the cache.
    cache.put(TEST_ITEM_A_A.getResourceId(), TEST_ITEM_A_A);

    GoogleCloudStorageItemInfo result = gcs.getItemInfo(TEST_ITEM_A_A.getResourceId());

    // Verify the cached item was returned.
    assertEquals(result, TEST_ITEM_A_A);
  }

  @Test
  public void testGetItemInfoMissing() throws IOException {
    GoogleCloudStorageItemInfo result = gcs.getItemInfo(TEST_ITEM_A_A.getResourceId());

    // Verify the delegate call.
    verify(gcsDelegate).getItemInfo(eq(TEST_ITEM_A_A.getResourceId()));
    assertEquals(result, TEST_ITEM_A_A);
    // Verify the cache was updated.
    assertEquals(cache.getIfPresent(TEST_ITEM_A_A.getResourceId()), TEST_ITEM_A_A);
  }

  @Test
  public void testGetItemInfosAllCached() throws IOException {
    List<StorageResourceId> requestedIds =
        Lists.newArrayList(TEST_ITEM_A_A.getResourceId(), TEST_ITEM_A_B.getResourceId());
    List<GoogleCloudStorageItemInfo> expected = Lists.newArrayList(TEST_ITEM_A_A, TEST_ITEM_A_B);

    // Prepare the cache.
    cache.put(TEST_ITEM_A_A.getResourceId(), TEST_ITEM_A_A);
    cache.put(TEST_ITEM_A_B.getResourceId(), TEST_ITEM_A_B);

    List<GoogleCloudStorageItemInfo> result = gcs.getItemInfos(requestedIds);

    // Verify the result is exactly what the delegate returns.
    assertContainsInAnyOrder(result, expected);
    // Verify ordering
    assertEquals(result.get(0), TEST_ITEM_A_A);
    assertEquals(result.get(1), TEST_ITEM_A_B);
  }

  @Test
  public void testGetItemInfosSomeCached() throws IOException {
    List<StorageResourceId> requestedIds =
        Lists.newArrayList(
            TEST_ITEM_A_A.getResourceId(), // Not cached
            TEST_ITEM_A_B.getResourceId(), // Cached
            TEST_ITEM_B_A.getResourceId(), // Not cached
            TEST_ITEM_B_B.getResourceId()); // Cached
    List<StorageResourceId> uncachedIds =
        Lists.newArrayList(TEST_ITEM_A_A.getResourceId(), TEST_ITEM_B_A.getResourceId());
    List<GoogleCloudStorageItemInfo> expected =
        Lists.newArrayList(TEST_ITEM_A_A, TEST_ITEM_A_B, TEST_ITEM_B_A, TEST_ITEM_B_B);

    // Prepare the cache.
    cache.put(TEST_ITEM_A_B.getResourceId(), TEST_ITEM_A_B);
    cache.put(TEST_ITEM_B_B.getResourceId(), TEST_ITEM_B_B);

    List<GoogleCloudStorageItemInfo> result = gcs.getItemInfos(requestedIds);

    // Verify the delegate call.
    verify(gcsDelegate).getItemInfos(eq(uncachedIds));
    assertContainsInAnyOrder(result, expected);
    // Verify ordering.
    assertEquals(result.get(0), TEST_ITEM_A_A);
    assertEquals(result.get(1), TEST_ITEM_A_B);
    assertEquals(result.get(2), TEST_ITEM_B_A);
    assertEquals(result.get(3), TEST_ITEM_B_B);
    // Verify the cache was updated.
    assertEquals(cache.getIfPresent(TEST_ITEM_A_A.getResourceId()), TEST_ITEM_A_A);
    assertEquals(cache.getIfPresent(TEST_ITEM_A_B.getResourceId()), TEST_ITEM_A_B);
    assertEquals(cache.getIfPresent(TEST_ITEM_B_A.getResourceId()), TEST_ITEM_B_A);
    assertEquals(cache.getIfPresent(TEST_ITEM_B_B.getResourceId()), TEST_ITEM_B_B);
  }

  @Test
  public void testGetItemInfosNoneCached() throws IOException {
    List<StorageResourceId> requestedIds =
        Lists.newArrayList(TEST_ITEM_A_A.getResourceId(), TEST_ITEM_A_B.getResourceId());
    List<GoogleCloudStorageItemInfo> expected = Lists.newArrayList(TEST_ITEM_A_A, TEST_ITEM_A_B);

    List<GoogleCloudStorageItemInfo> result = gcs.getItemInfos(requestedIds);

    // Verify the delegate call.
    verify(gcsDelegate).getItemInfos(eq(requestedIds));
    assertContainsInAnyOrder(result, expected);
    // Verify ordering
    assertEquals(result.get(0), TEST_ITEM_A_A);
    assertEquals(result.get(1), TEST_ITEM_A_B);
    // Verify the cache was updated.
    assertEquals(cache.getIfPresent(TEST_ITEM_A_A.getResourceId()), TEST_ITEM_A_A);
    assertEquals(cache.getIfPresent(TEST_ITEM_A_B.getResourceId()), TEST_ITEM_A_B);
  }

  @Test
  public void testUpdateItems() throws IOException {
    List<GoogleCloudStorageItemInfo> expected = Lists.newArrayList(TEST_ITEM_A_A);
    List<UpdatableItemInfo> updateItems =
        Lists.newArrayList(new UpdatableItemInfo(TEST_ITEM_A_A.getResourceId(), TEST_METADATA));

    List<GoogleCloudStorageItemInfo> result = gcs.updateItems(updateItems);

    // Verify the delegate call.
    verify(gcsDelegate).updateItems(eq(updateItems));
    assertContainsInAnyOrder(result, expected);
    // Verify the cache was updated.
    assertEquals(cache.getIfPresent(TEST_ITEM_A_A.getResourceId()), TEST_ITEM_A_A);
  }

  @Test
  public void testClose() {
    // Prepare the cache.
    cache.put(TEST_ITEM_A_A.getResourceId(), TEST_ITEM_A_A);

    gcs.close();

    // Verify the delegate call was made.
    verify(gcsDelegate).close();
    // Verify the cache was updated.
    assertEquals(cache.getIfPresent(TEST_ITEM_A_A.getResourceId()), null);
  }

  @Test
  public void testComposeObjects() throws IOException {
    List<StorageResourceId> ids =
        Lists.newArrayList(TEST_ITEM_B_A.getResourceId(), TEST_ITEM_B_B.getResourceId());

    GoogleCloudStorageItemInfo result =
        gcs.composeObjects(ids, TEST_ITEM_B_C.getResourceId(), CREATE_OBJECT_OPTIONS);

    // Verify the delegate call.
    verify(gcsDelegate)
        .composeObjects(eq(ids), eq(TEST_ITEM_B_C.getResourceId()), eq(CREATE_OBJECT_OPTIONS));
    assertEquals(result, TEST_ITEM_B_C);
    // Verify the cache was updated.
    assertEquals(cache.getIfPresent(TEST_ITEM_B_C.getResourceId()), TEST_ITEM_B_C);
  }

  /**
   * Helper to generate GoogleCloudStorageItemInfo for a bucket entry.
   *
   * @param bucketName the name of the bucket.
   * @return the generated item.
   */
  private static GoogleCloudStorageItemInfo createBucketItemInfo(String bucketName) {
    GoogleCloudStorageItemInfo item =
        new GoogleCloudStorageItemInfo(
            new StorageResourceId(bucketName),
            0,
            0,
            CREATE_BUCKET_OPTIONS.getLocation(),
            CREATE_BUCKET_OPTIONS.getStorageClass());
    return item;
  }

  /**
   * Helper to generate a GoogleCloudStorageItemInfo for an object entry.
   *
   * @param bucketName the name of the bucket for the generated item.
   * @param objectName the object name of the generated item.
   * @return the generated item.
   */
  private static GoogleCloudStorageItemInfo createObjectItemInfo(
      String bucketName, String objectName) {
    GoogleCloudStorageItemInfo item =
        new GoogleCloudStorageItemInfo(
            new StorageResourceId(bucketName, objectName),
            0,
            0,
            null,
            null,
            CREATE_OBJECT_OPTIONS.getContentType(),
            TEST_METADATA,
            0,
            0L);
    return item;
  }

  /**
   * Helper method for comparing collections of GoogleCloudStorageItemInfos. Only checks equality
   * based on the item's resource id.
   *
   * @param actualItems the actual collect of GoogleCloudStorageItemInfo.
   * @param expectedItems the collection of GoogleCloudStorageItemInfo that was expected.
   */
  private static void assertContainsInAnyOrder(
      Collection<GoogleCloudStorageItemInfo> actualItems,
      Collection<GoogleCloudStorageItemInfo> expectedItems) {
    HashMap<StorageResourceId, GoogleCloudStorageItemInfo> expectedHash =
        new HashMap<StorageResourceId, GoogleCloudStorageItemInfo>();

    StringJoiner missingString = new StringJoiner(",", "[", "]");
    StringJoiner expectedString = new StringJoiner(",", "[", "]");
    boolean missing = false;

    for (GoogleCloudStorageItemInfo expectedItem : expectedItems) {
      expectedHash.put(expectedItem.getResourceId(), expectedItem);
      expectedString.add(expectedItem.getResourceId().toString());
    }

    for (GoogleCloudStorageItemInfo actualItem : actualItems) {
      GoogleCloudStorageItemInfo expectedItem = expectedHash.get(actualItem.getResourceId());
      if (expectedItem == null || !equals(actualItem, expectedItem)) {
        missingString.add(actualItem.getResourceId().toString());
        missing = true;
      }
    }

    if (missing) {
      throw new AssertionError(
          String.format(
              "\nExpected: %s in any order\n     but: %s were not matched",
              expectedString, missingString));
    }
  }

  /**
   * Helper method for comparing GoogleCloudStorageItemInfo. Checks based on resource id, location,
   * storage class, content type, and metadata.
   *
   * @param actual the actual object.
   * @param expected the expected object.
   * @throws AssertionError if the objects are not equal.
   */
  private static void assertEquals(
      GoogleCloudStorageItemInfo actual, GoogleCloudStorageItemInfo expected) {
    if (!equals(actual, expected)) {
      throw new AssertionError(String.format("\nExpected: %s\n but was: %s ", expected, actual));
    }
  }

  /**
   * Helper method for comparing GoogleCloudStorageItemInfo. Checks based on resource id, location,
   * storage class, content type, and metadata.
   *
   * @param a a GoogleCloudStorageItemInfo.
   * @param b a GoogleCloudStorageItemInfo to be compared with a.
   * @return true of the arguments are equal, false otherwise.
   */
  private static boolean equals(GoogleCloudStorageItemInfo a, GoogleCloudStorageItemInfo b) {
    if (a == b) {
      return true;
    } else if (a == null || b == null) {
      return false;
    } else {
      return a.getResourceId().equals(b.getResourceId())
          && Objects.equals(a.getLocation(), b.getLocation())
          && Objects.equals(a.getStorageClass(), b.getStorageClass())
          && Objects.equals(a.getContentType(), b.getContentType())
          && a.metadataEquals(b.getMetadata());
    }
  }

  /** Ticker with a manual time value used for testing the cache. */
  private static class TestTicker extends Ticker {

    @Override
    public long read() {
      return 0L;
    }
  }

  /** Clock with a manual time value used for testing the GCS delegate. */
  private static class TestClock implements Clock {

    @Override
    public long currentTimeMillis() {
      return 0L;
    }
  }
}
