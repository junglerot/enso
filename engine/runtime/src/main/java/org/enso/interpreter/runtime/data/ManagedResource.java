package org.enso.interpreter.runtime.data;

import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import org.enso.interpreter.dsl.Builtin;
import org.enso.interpreter.runtime.EnsoContext;
import org.enso.interpreter.runtime.callable.function.Function;
import org.enso.interpreter.runtime.library.dispatch.TypesLibrary;

import java.lang.ref.PhantomReference;

/** A runtime representation of a managed resource. */
@ExportLibrary(TypesLibrary.class)
@Builtin(pkg = "resource", stdlibName = "Standard.Base.Runtime.Resource.Managed_Resource")
public final class ManagedResource implements TruffleObject {
  private final Object resource;
  private PhantomReference<ManagedResource> phantomReference;

  /**
   * Creates a new managed resource.
   *
   * @param resource the underlying resource
   */
  public ManagedResource(Object resource) {
    this.resource = resource;
    this.phantomReference = null;
  }

  /** @return the underlying resource */
  public Object getResource() {
    return resource;
  }

  /** @return the phantom reference tracking this managed resource */
  public PhantomReference<ManagedResource> getPhantomReference() {
    return phantomReference;
  }

  /**
   * Sets the value of the reference used to track reachability of this managed resource.
   *
   * @param phantomReference the phantom reference tracking this managed resource.
   */
  public void setPhantomReference(PhantomReference<ManagedResource> phantomReference) {
    this.phantomReference = phantomReference;
  }

  @Builtin.Method(
      description =
          "Makes an object into a managed resource, automatically finalized when the returned object is garbage collected.")
  @Builtin.Specialize
  public static ManagedResource register(EnsoContext context, Object resource, Function function) {
    return context.getResourceManager().register(resource, function);
  }

  @Builtin.Method(
      description =
          "Takes the value held by the managed resource and removes the finalization callbacks,"
              + " effectively making the underlying resource unmanaged again.")
  @Builtin.Specialize
  public Object take(EnsoContext context) {
    context.getResourceManager().take(this);
    return this.getResource();
  }

  @Builtin.Method(
      name = "finalize",
      description = "Finalizes a managed resource, even if it is still reachable.")
  @Builtin.Specialize
  public void close(EnsoContext context) {
    context.getResourceManager().close(this);
  }

  @ExportMessage
  boolean hasType() {
    return true;
  }

  @ExportMessage
  Type getType(@CachedLibrary("this") TypesLibrary thisLib) {
    return EnsoContext.get(thisLib).getBuiltins().managedResource();
  }
}
