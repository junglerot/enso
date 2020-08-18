package org.enso.interpreter.instrument;

import java.lang.ref.SoftReference;
import java.util.*;

/** A storage for computed values. */
public final class RuntimeCache {

  private final Map<UUID, SoftReference<Object>> cache = new HashMap<>();
  private final Map<UUID, String> types = new HashMap<>();
  private final Map<UUID, IdExecutionInstrument.FunctionCallInfo> calls = new HashMap<>();
  private Map<UUID, Double> weights = new HashMap<>();

  /**
   * Add value to the cache if it is possible.
   *
   * @param key the key of an entry.
   * @param value the added value.
   * @return {@code true} if the value was added to the cache.
   */
  public boolean offer(UUID key, Object value) {
    Double weight = weights.get(key);
    if (weight != null && weight > 0) {
      cache.put(key, new SoftReference<>(value));
      return true;
    }
    return false;
  }

  /** Get the value from the cache. */
  public Object get(UUID key) {
    SoftReference<Object> ref = cache.get(key);
    return ref != null ? ref.get() : null;
  }

  /** Remove the value from the cache. */
  public Object remove(UUID key) {
    SoftReference<Object> ref = cache.remove(key);
    return ref == null ? null : ref.get();
  }

  /** @return all cache keys. */
  public Set<UUID> getKeys() {
    return cache.keySet();
  }

  /** Clear the cached values. */
  public void clear() {
    cache.clear();
  }

  /**
   * Cache the type of expression.
   *
   * @return the previously cached type.
   */
  public String putType(UUID key, String typeName) {
    return types.put(key, typeName);
  }

  /** @return the cached type of the expression */
  public String getType(UUID key) {
    return types.get(key);
  }

  /**
   * Cache the function call
   *
   * @param key the expression associated with the function call.
   * @param call the function call.
   * @return the function call that was previously associated with this expression.
   */
  public IdExecutionInstrument.FunctionCallInfo putCall(
      UUID key, IdExecutionInstrument.FunctionCallInfo call) {
    if (call == null) {
      return calls.remove(key);
    }
    return calls.put(key, call);
  }

  /** @return the cached function call associated with the expression. */
  public IdExecutionInstrument.FunctionCallInfo getCall(UUID key) {
    return calls.get(key);
  }

  /** @return the cached method calls. */
  public Set<UUID> getCalls() {
    return calls.keySet();
  }

  /** Clear the cached calls. */
  public void clearCalls() {
    calls.clear();
  }

  /** Remove the type associated with the provided key. */
  public void removeType(UUID key) {
    types.remove(key);
  }

  /** Clear the cached types. */
  public void clearTypes() {
    types.clear();
  }

  /** @return the weights of this cache. */
  public Map<UUID, Double> getWeights() {
    return weights;
  }

  /** Set the new weights. */
  public void setWeights(Map<UUID, Double> weights) {
    this.weights = weights;
  }

  /** Remove the weight associated with the provided key. */
  public void removeWeight(UUID key) {
    weights.remove(key);
  }

  /** Clear the weights. */
  public void clearWeights() {
    weights.clear();
  }
}
