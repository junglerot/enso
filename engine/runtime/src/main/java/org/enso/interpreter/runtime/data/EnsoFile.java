package org.enso.interpreter.runtime.data;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.function.IntFunction;

import org.enso.interpreter.dsl.Builtin;
import org.enso.interpreter.runtime.EnsoContext;
import org.enso.interpreter.runtime.error.PanicException;
import org.enso.interpreter.runtime.library.dispatch.TypesLibrary;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.CopyOption;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Set;

import org.enso.interpreter.runtime.data.vector.ArrayLikeHelpers;

import com.oracle.truffle.api.dsl.Cached;

/**
 * A wrapper for {@link TruffleFile} objects exposed to the language. For methods documentation
 * please refer to {@link TruffleFile}.
 */
@ExportLibrary(InteropLibrary.class)
@ExportLibrary(TypesLibrary.class)
@Builtin(pkg = "io", name = "File", stdlibName = "Standard.Base.System.File.File")
public final class EnsoFile implements EnsoObject {
  private final TruffleFile truffleFile;

  public EnsoFile(TruffleFile truffleFile) {
    if (truffleFile == null) {
      throw CompilerDirectives.shouldNotReachHere();
    }
    this.truffleFile = truffleFile;
  }

  @Builtin.Method(name = "output_stream_builtin")
  @Builtin.WrapException(from = IOException.class)
  @Builtin.ReturningGuestObject
  @Builtin.Specialize
  @CompilerDirectives.TruffleBoundary
  public OutputStream outputStream(Object opts, EnsoContext ctx) throws IOException {
    OpenOption[] openOptions = convertInteropArray(opts, InteropLibrary.getUncached(), ctx, OpenOption[]::new);
    return this.truffleFile.newOutputStream(openOptions);
  }

  @Builtin.Method(name = "input_stream_builtin")
  @Builtin.WrapException(from = IOException.class)
  @Builtin.Specialize
  @Builtin.ReturningGuestObject
  @CompilerDirectives.TruffleBoundary
  public InputStream inputStream(Object opts, EnsoContext ctx) throws IOException {
    OpenOption[] openOptions = convertInteropArray(opts, InteropLibrary.getUncached(), ctx, OpenOption[]::new);
    return this.truffleFile.newInputStream(openOptions);
  }

  @SuppressWarnings("unchecked")
  private static <T> T[] convertInteropArray(Object arr,
      InteropLibrary interop,
      EnsoContext ctx,
      IntFunction<T[]> hostArrayCtor) {
    if (!interop.hasArrayElements(arr)) {
      var vecType = ctx.getBuiltins().vector().getType();
      var typeError = ctx.getBuiltins().error().makeTypeError(vecType, arr, "opts");
      throw new PanicException(typeError, interop);
    }
    T[] hostArr;
    try {
      int size = Math.toIntExact(interop.getArraySize(arr));
      hostArr = hostArrayCtor.apply(size);
      for (int i = 0; i < size; i++) {
        Object elem = interop.readArrayElement(arr, i);
        if (!ctx.isJavaPolyglotObject(elem)) {
          var err = ctx.getBuiltins().error().makeUnsupportedArgumentsError(
              new Object[]{arr},
              "Arguments to opts should be host objects from java.io package"
          );
          throw new PanicException(err, interop);
        }
        hostArr[i] = (T) ctx.asJavaPolyglotObject(elem);
      }
    } catch (ClassCastException | UnsupportedMessageException | InvalidArrayIndexException e) {
      throw EnsoContext.get(interop).raiseAssertionPanic(interop, null, e);
    }
    return hostArr;
  }

  @Builtin.Method(name = "read_last_bytes_builtin")
  @Builtin.WrapException(from = IOException.class)
  @CompilerDirectives.TruffleBoundary
  public EnsoObject readLastBytes(long n) throws IOException {
    try (SeekableByteChannel channel =
        this.truffleFile.newByteChannel(Set.of(StandardOpenOption.READ))) {
      int bytesToRead = Math.toIntExact(Math.min(channel.size(), n));
      channel.position(channel.size() - bytesToRead);
      ByteBuffer buffer = ByteBuffer.allocate(bytesToRead);
      while (buffer.hasRemaining()) {
        channel.read(buffer);
      }

      buffer.flip();
      return ArrayLikeHelpers.wrapBuffer(buffer);
    }
  }

  @Builtin.Method(name = "resolve")
  @Builtin.Specialize
  public EnsoFile resolve(String subPath) {
    return new EnsoFile(this.truffleFile.resolve(subPath));
  }

  @Builtin.Method(name = "resolve")
  @Builtin.Specialize
  public EnsoFile resolve(EnsoFile subPath) {
    return new EnsoFile(this.truffleFile.resolve(subPath.truffleFile.getPath()));
  }

  @Builtin.Method
  public boolean exists() {
    return truffleFile.exists();
  }

  @Builtin.Method(name = "creation_time_builtin")
  @Builtin.WrapException(from = IOException.class)
  @Builtin.ReturningGuestObject
  @CompilerDirectives.TruffleBoundary
  public EnsoDateTime getCreationTime() throws IOException {
    return new EnsoDateTime(
        ZonedDateTime.ofInstant(truffleFile.getCreationTime().toInstant(), ZoneOffset.UTC));
  }

  @Builtin.Method(name = "last_modified_time_builtin")
  @Builtin.WrapException(from = IOException.class)
  @Builtin.ReturningGuestObject
  @CompilerDirectives.TruffleBoundary
  public EnsoDateTime getLastModifiedTime() throws IOException {
    return new EnsoDateTime(
        ZonedDateTime.ofInstant(truffleFile.getLastModifiedTime().toInstant(), ZoneOffset.UTC));
  }

  @Builtin.Method(name = "posix_permissions_builtin")
  @Builtin.WrapException(from = IOException.class)
  @Builtin.ReturningGuestObject
  @CompilerDirectives.TruffleBoundary
  public Set<PosixFilePermission> getPosixPermissions() throws IOException {
    return truffleFile.getPosixPermissions();
  }

  @Builtin.Method(name = "parent")
  @CompilerDirectives.TruffleBoundary
  public Object getParent() {
    var parentOrNull = this.truffleFile.getParent();
    if (parentOrNull != null) {
      return new EnsoFile(parentOrNull);
    } else {
      var ctx = EnsoContext.get(null);
      return ctx.getBuiltins().nothing();
    }
  }

  @Builtin.Method(name = "absolute")
  @CompilerDirectives.TruffleBoundary
  public EnsoFile getAbsoluteFile() {
    return new EnsoFile(this.truffleFile.getAbsoluteFile());
  }

  @Builtin.Method(name = "path")
  @CompilerDirectives.TruffleBoundary
  public String getPath() {
    return this.truffleFile.getPath();
  }

  @Builtin.Method
  @CompilerDirectives.TruffleBoundary
  public boolean isAbsolute() {
    return this.truffleFile.isAbsolute();
  }

  @Builtin.Method
  @CompilerDirectives.TruffleBoundary
  public boolean isDirectory() {
    return this.truffleFile.isDirectory();
  }

  @Builtin.Method(name = "create_directory_builtin")
  @CompilerDirectives.TruffleBoundary
  public void createDirectories() {
    try {
      this.truffleFile.createDirectories();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  @Builtin.Method(name = "list_immediate_children_array")
  @Builtin.WrapException(from = IOException.class)
  @CompilerDirectives.TruffleBoundary
  public EnsoObject list() throws IOException {
    return ArrayLikeHelpers.wrapEnsoObjects(this.truffleFile.list().stream().map(EnsoFile::new).toArray(EnsoFile[]::new));
  }

  @Builtin.Method
  @CompilerDirectives.TruffleBoundary
  public EnsoFile relativize(EnsoFile other) {
    return new EnsoFile(this.truffleFile.relativize(other.truffleFile));
  }

  @Builtin.Method
  @CompilerDirectives.TruffleBoundary
  public boolean isRegularFile() {
    return this.truffleFile.isRegularFile();
  }

  @Builtin.Method
  @CompilerDirectives.TruffleBoundary
  public boolean isWritable() {
    return this.truffleFile.isWritable();
  }

  @Builtin.Method(name = "name")
  @CompilerDirectives.TruffleBoundary
  public String getName() {
    return this.truffleFile.getName();
  }

  @Builtin.Method(name = "size_builtin")
  @Builtin.WrapException(from = IOException.class)
  @CompilerDirectives.TruffleBoundary
  public long getSize() throws IOException {
    if ( this.truffleFile.isDirectory()) {
      throw new IOException("size can only be called on files.");
    }
    return this.truffleFile.size();
  }

  @TruffleBoundary
  @Override
  public boolean equals(Object obj) {
    if (obj instanceof EnsoFile otherFile) {
      return truffleFile.getPath().equals(otherFile.truffleFile.getPath());
    } else {
      return false;
    }
  }

  @Builtin.Method
  @CompilerDirectives.TruffleBoundary
  public EnsoFile normalize() {
    return new EnsoFile(truffleFile.normalize());
  }

  @Builtin.Method(name = "delete_builtin")
  @Builtin.WrapException(from = IOException.class)
  @CompilerDirectives.TruffleBoundary
  public void delete() throws IOException {
    truffleFile.delete();
  }

  @Builtin.Method(name = "copy_builtin", description = "Copy this file to a target destination")
  @Builtin.WrapException(from = IOException.class)
  @Builtin.Specialize
  @CompilerDirectives.TruffleBoundary
  public void copy(EnsoFile target, Object options, EnsoContext ctx) throws IOException {
    CopyOption[] copyOptions = convertInteropArray(options, InteropLibrary.getUncached(), ctx, CopyOption[]::new);
    truffleFile.copy(target.truffleFile, copyOptions);
  }

  @Builtin.Method(name = "move_builtin", description = "Move this file to a target destination")
  @Builtin.WrapException(from = IOException.class)
  @Builtin.Specialize
  @CompilerDirectives.TruffleBoundary
  public void move(EnsoFile target, Object options,
      EnsoContext ctx) throws IOException {
    CopyOption[] copyOptions = convertInteropArray(options, InteropLibrary.getUncached(), ctx, CopyOption[]::new);
    truffleFile.move(target.truffleFile, copyOptions);
  }

  @Builtin.Method
  @CompilerDirectives.TruffleBoundary
  public boolean startsWith(EnsoFile parent) {
    return truffleFile.startsWith(parent.truffleFile);
  }

  @Builtin.Method(
      name = "get_file",
      description =
          "Takes the text representation of a path and returns a TruffleFile corresponding to it.",
      autoRegister = false)
  @Builtin.Specialize
  @CompilerDirectives.TruffleBoundary
  public static EnsoFile fromString(EnsoContext context, String path) {
    TruffleFile file = context.getPublicTruffleFile(path);
    return new EnsoFile(file);
  }

  @Builtin.Method(
      name = "get_cwd",
      description = "A file corresponding to the current working directory.",
      autoRegister = false)
  @Builtin.Specialize
  @CompilerDirectives.TruffleBoundary
  public static EnsoFile currentDirectory(EnsoContext context) {
    TruffleFile file = context.getCurrentWorkingDirectory();
    return new EnsoFile(file);
  }

  @Builtin.Method(
      name = "home",
      description = "Gets the user's system-defined home directory.",
      autoRegister = false)
  @Builtin.Specialize
  @CompilerDirectives.TruffleBoundary
  public static EnsoFile userHome(EnsoContext context) {
    return fromString(context, System.getProperty("user.home"));
  }

  @Override
  @CompilerDirectives.TruffleBoundary
  public String toString() {
    return "(File " + truffleFile.getPath() + ")";
  }

  @ExportMessage
  Type getMetaObject(@CachedLibrary("this") InteropLibrary thisLib) {
    return EnsoContext.get(thisLib).getBuiltins().file();
  }

  @ExportMessage
  boolean hasMetaObject() {
    return true;
  }

  @ExportMessage
  boolean hasType() {
    return true;
  }

  @ExportMessage
  Type getType(@CachedLibrary("this") TypesLibrary thisLib, @Cached("1") int ignore) {
    return EnsoContext.get(thisLib).getBuiltins().file();
  }
}
