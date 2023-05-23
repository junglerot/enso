package org.enso.interpreter.node.expression.builtin.meta;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;

import org.enso.interpreter.dsl.AcceptsError;
import org.enso.interpreter.node.expression.builtin.interop.syntax.HostValueToEnsoNode;
import org.enso.interpreter.node.expression.builtin.ordering.CustomComparatorNode;
import org.enso.interpreter.runtime.EnsoContext;
import org.enso.interpreter.runtime.Module;
import org.enso.interpreter.runtime.callable.UnresolvedConversion;
import org.enso.interpreter.runtime.callable.UnresolvedSymbol;
import org.enso.interpreter.runtime.callable.atom.Atom;
import org.enso.interpreter.runtime.data.EnsoFile;
import org.enso.interpreter.runtime.data.Type;
import org.enso.interpreter.runtime.error.WarningsLibrary;
import org.enso.interpreter.runtime.library.dispatch.TypesLibrary;
import org.enso.interpreter.runtime.scope.ModuleScope;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.StopIterationException;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnknownKeyException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.Node;

@GenerateUncached
public abstract class EqualsComplexNode extends Node {

  public static EqualsComplexNode build() {
    return EqualsComplexNodeGen.create();
  }

  public abstract boolean execute(@AcceptsError Object left, @AcceptsError Object right);

  /** Enso specific types */
  @Specialization
  boolean equalsUnresolvedSymbols(
      UnresolvedSymbol self, UnresolvedSymbol otherSymbol, @Cached EqualsNode equalsNode) {
    return self.getName().equals(otherSymbol.getName())
        && equalsNode.execute(self.getScope(), otherSymbol.getScope());
  }

  @Specialization
  boolean equalsUnresolvedConversion(
      UnresolvedConversion selfConversion,
      UnresolvedConversion otherConversion,
      @Cached EqualsNode equalsNode) {
    return equalsNode.execute(selfConversion.getScope(), otherConversion.getScope());
  }

  @Specialization
  boolean equalsModuleScopes(
      ModuleScope selfModuleScope, ModuleScope otherModuleScope, @Cached EqualsNode equalsNode) {
    return equalsNode.execute(selfModuleScope.getModule(), otherModuleScope.getModule());
  }

  @Specialization
  @TruffleBoundary
  boolean equalsModules(Module selfModule, Module otherModule, @Cached EqualsNode equalsNode) {
    return equalsNode.execute(selfModule.getName().toString(), otherModule.getName().toString());
  }

  @Specialization
  boolean equalsFiles(EnsoFile selfFile, EnsoFile otherFile, @Cached EqualsNode equalsNode) {
    return equalsNode.execute(selfFile.getPath(), otherFile.getPath());
  }

  /**
   * There is no specialization for {@link TypesLibrary#hasType(Object)}, because also primitive
   * values would fall into that specialization, and it would be too complicated to make that
   * specialization disjunctive. So we rather specialize directly for {@link Type types}.
   */
  @Specialization(guards = {"typesLib.hasType(selfType)", "typesLib.hasType(otherType)"})
  boolean equalsTypes(
      Type selfType,
      Type otherType,
      @Cached EqualsNode equalsNode,
      @CachedLibrary(limit = "5") TypesLibrary typesLib) {
    return equalsNode.execute(
        selfType.getQualifiedName().toString(), otherType.getQualifiedName().toString());
  }

  /**
   * If one of the objects has warnings attached, just treat it as an object without any warnings.
   */
  @Specialization(
      guards = {
        "selfWarnLib.hasWarnings(selfWithWarnings) || otherWarnLib.hasWarnings(otherWithWarnings)"
      },
      limit = "3")
  boolean equalsWithWarnings(
      Object selfWithWarnings,
      Object otherWithWarnings,
      @CachedLibrary("selfWithWarnings") WarningsLibrary selfWarnLib,
      @CachedLibrary("otherWithWarnings") WarningsLibrary otherWarnLib,
      @Cached EqualsNode equalsNode) {
    try {
      Object self =
          selfWarnLib.hasWarnings(selfWithWarnings)
              ? selfWarnLib.removeWarnings(selfWithWarnings)
              : selfWithWarnings;
      Object other =
          otherWarnLib.hasWarnings(otherWithWarnings)
              ? otherWarnLib.removeWarnings(otherWithWarnings)
              : otherWithWarnings;
      return equalsNode.execute(self, other);
    } catch (UnsupportedMessageException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Interop libraries * */
  @Specialization(
      guards = {
        "selfInterop.isNull(selfNull) || otherInterop.isNull(otherNull)",
      },
      limit = "3")
  boolean equalsNull(
      Object selfNull,
      Object otherNull,
      @CachedLibrary("selfNull") InteropLibrary selfInterop,
      @CachedLibrary("otherNull") InteropLibrary otherInterop) {
    return selfInterop.isNull(selfNull) && otherInterop.isNull(otherNull);
  }

  @Specialization(
      guards = {"selfInterop.isBoolean(selfBoolean)", "otherInterop.isBoolean(otherBoolean)"},
      limit = "3")
  boolean equalsBooleanInterop(
      Object selfBoolean,
      Object otherBoolean,
      @CachedLibrary("selfBoolean") InteropLibrary selfInterop,
      @CachedLibrary("otherBoolean") InteropLibrary otherInterop) {
    try {
      return selfInterop.asBoolean(selfBoolean) == otherInterop.asBoolean(otherBoolean);
    } catch (UnsupportedMessageException e) {
      throw new IllegalStateException(e);
    }
  }

  @Specialization(
      guards = {
        "isTimeZone(selfTimeZone, selfInterop)",
        "isTimeZone(otherTimeZone, otherInterop)",
      },
      limit = "3")
  boolean equalsTimeZones(
      Object selfTimeZone,
      Object otherTimeZone,
      @CachedLibrary("selfTimeZone") InteropLibrary selfInterop,
      @CachedLibrary("otherTimeZone") InteropLibrary otherInterop) {
    try {
      return selfInterop.asTimeZone(selfTimeZone).equals(otherInterop.asTimeZone(otherTimeZone));
    } catch (UnsupportedMessageException e) {
      throw new IllegalStateException(e);
    }
  }

  @TruffleBoundary
  @Specialization(
      guards = {
        "isZonedDateTime(selfZonedDateTime, selfInterop)",
        "isZonedDateTime(otherZonedDateTime, otherInterop)",
      },
      limit = "3")
  boolean equalsZonedDateTimes(
      Object selfZonedDateTime,
      Object otherZonedDateTime,
      @CachedLibrary("selfZonedDateTime") InteropLibrary selfInterop,
      @CachedLibrary("otherZonedDateTime") InteropLibrary otherInterop) {
    try {
      var self =
          ZonedDateTime.of(
              selfInterop.asDate(selfZonedDateTime),
              selfInterop.asTime(selfZonedDateTime),
              selfInterop.asTimeZone(selfZonedDateTime));
      var other =
          ZonedDateTime.of(
              otherInterop.asDate(otherZonedDateTime),
              otherInterop.asTime(otherZonedDateTime),
              otherInterop.asTimeZone(otherZonedDateTime));
      // We cannot use self.isEqual(other), because that does not include timezone.
      return self.compareTo(other) == 0;
    } catch (UnsupportedMessageException e) {
      throw new IllegalStateException(e);
    }
  }

  @Specialization(
      guards = {
        "isDateTime(selfDateTime, selfInterop)",
        "isDateTime(otherDateTime, otherInterop)",
      },
      limit = "3")
  boolean equalsDateTimes(
      Object selfDateTime,
      Object otherDateTime,
      @CachedLibrary("selfDateTime") InteropLibrary selfInterop,
      @CachedLibrary("otherDateTime") InteropLibrary otherInterop) {
    try {
      var self =
          LocalDateTime.of(selfInterop.asDate(selfDateTime), selfInterop.asTime(selfDateTime));
      var other =
          LocalDateTime.of(otherInterop.asDate(otherDateTime), otherInterop.asTime(otherDateTime));
      return self.isEqual(other);
    } catch (UnsupportedMessageException e) {
      throw new IllegalStateException(e);
    }
  }

  @Specialization(
      guards = {
        "isDate(selfDate, selfInterop)",
        "isDate(otherDate, otherInterop)",
      },
      limit = "3")
  boolean equalsDates(
      Object selfDate,
      Object otherDate,
      @CachedLibrary("selfDate") InteropLibrary selfInterop,
      @CachedLibrary("otherDate") InteropLibrary otherInterop) {
    try {
      return selfInterop.asDate(selfDate).isEqual(otherInterop.asDate(otherDate));
    } catch (UnsupportedMessageException e) {
      throw new IllegalStateException(e);
    }
  }

  @Specialization(
      guards = {
        "isTime(selfTime, selfInterop)",
        "isTime(otherTime, otherInterop)",
      },
      limit = "3")
  boolean equalsTimes(
      Object selfTime,
      Object otherTime,
      @CachedLibrary("selfTime") InteropLibrary selfInterop,
      @CachedLibrary("otherTime") InteropLibrary otherInterop) {
    try {
      return selfInterop.asTime(selfTime).equals(otherInterop.asTime(otherTime));
    } catch (UnsupportedMessageException e) {
      throw new IllegalStateException(e);
    }
  }

  @Specialization(
      guards = {"selfInterop.isDuration(selfDuration)", "otherInterop.isDuration(otherDuration)"},
      limit = "3")
  boolean equalsDuration(
      Object selfDuration,
      Object otherDuration,
      @CachedLibrary("selfDuration") InteropLibrary selfInterop,
      @CachedLibrary("otherDuration") InteropLibrary otherInterop) {
    try {
      return selfInterop.asDuration(selfDuration).equals(otherInterop.asDuration(otherDuration));
    } catch (UnsupportedMessageException e) {
      throw new IllegalStateException(e);
    }
  }

  @Specialization(
      guards = {
        "selfInterop.hasArrayElements(selfArray)",
        "otherInterop.hasArrayElements(otherArray)",
        "!selfInterop.hasHashEntries(selfArray)",
        "!otherInterop.hasHashEntries(otherArray)",
      },
      limit = "3")
  boolean equalsArrays(
      Object selfArray,
      Object otherArray,
      @CachedLibrary("selfArray") InteropLibrary selfInterop,
      @CachedLibrary("otherArray") InteropLibrary otherInterop,
      @Cached EqualsNode equalsNode,
      @Cached CustomComparatorNode hasCustomComparatorNode,
      @Cached HostValueToEnsoNode valueToEnsoNode) {
    try {
      long selfSize = selfInterop.getArraySize(selfArray);
      if (selfSize != otherInterop.getArraySize(otherArray)) {
        return false;
      }
      for (long i = 0; i < selfSize; i++) {
        Object selfElem = valueToEnsoNode.execute(selfInterop.readArrayElement(selfArray, i));
        Object otherElem = valueToEnsoNode.execute(otherInterop.readArrayElement(otherArray, i));
        boolean elemsAreEqual = equalsNode.execute(selfElem, otherElem);
        if (!elemsAreEqual) {
          return false;
        }
      }
      return true;
    } catch (UnsupportedMessageException | InvalidArrayIndexException e) {
      throw new IllegalStateException(e);
    }
  }

  @Specialization(
      guards = {
        "selfInterop.hasHashEntries(selfHashMap)",
        "otherInterop.hasHashEntries(otherHashMap)",
        "!selfInterop.hasArrayElements(selfHashMap)",
        "!otherInterop.hasArrayElements(otherHashMap)"
      },
      limit = "3")
  boolean equalsHashMaps(
      Object selfHashMap,
      Object otherHashMap,
      @CachedLibrary("selfHashMap") InteropLibrary selfInterop,
      @CachedLibrary("otherHashMap") InteropLibrary otherInterop,
      @CachedLibrary(limit = "5") InteropLibrary entriesInterop,
      @Cached EqualsNode equalsNode,
      @Cached HostValueToEnsoNode keyToEnsoNode,
      @Cached HostValueToEnsoNode valueToEnsoNode) {
    try {
      int selfHashSize = (int) selfInterop.getHashSize(selfHashMap);
      int otherHashSize = (int) otherInterop.getHashSize(otherHashMap);
      if (selfHashSize != otherHashSize) {
        return false;
      }
      Object selfEntriesIter = selfInterop.getHashEntriesIterator(selfHashMap);
      while (entriesInterop.hasIteratorNextElement(selfEntriesIter)) {
        Object selfKeyValue = entriesInterop.getIteratorNextElement(selfEntriesIter);
        Object key = keyToEnsoNode.execute(entriesInterop.readArrayElement(selfKeyValue, 0));
        Object selfValue =
            valueToEnsoNode.execute(entriesInterop.readArrayElement(selfKeyValue, 1));
        if (otherInterop.isHashEntryExisting(otherHashMap, key)
            && otherInterop.isHashEntryReadable(otherHashMap, key)) {
          Object otherValue =
              valueToEnsoNode.execute(otherInterop.readHashValue(otherHashMap, key));
          if (!equalsNode.execute(selfValue, otherValue)) {
            return false;
          }
        } else {
          return false;
        }
      }
      return true;
    } catch (UnsupportedMessageException
        | StopIterationException
        | UnknownKeyException
        | InvalidArrayIndexException e) {
      throw new IllegalStateException(e);
    }
  }

  @Specialization(
      guards = {
        "isObjectWithMembers(selfObject, interop)",
        "isObjectWithMembers(otherObject, interop)",
      })
  boolean equalsInteropObjectWithMembers(
      Object selfObject,
      Object otherObject,
      @CachedLibrary(limit = "10") InteropLibrary interop,
      @CachedLibrary(limit = "5") TypesLibrary typesLib,
      @Cached EqualsNode equalsNode,
      @Cached HostValueToEnsoNode valueToEnsoNode) {
    try {
      Object selfMembers = interop.getMembers(selfObject);
      Object otherMembers = interop.getMembers(otherObject);
      assert interop.getArraySize(selfMembers) < Integer.MAX_VALUE
          : "Long array sizes not supported";
      int membersSize = (int) interop.getArraySize(selfMembers);
      if (interop.getArraySize(otherMembers) != membersSize) {
        return false;
      }

      // Check member names
      String[] memberNames = new String[membersSize];
      for (int i = 0; i < membersSize; i++) {
        String selfMemberName = interop.asString(interop.readArrayElement(selfMembers, i));
        String otherMemberName = interop.asString(interop.readArrayElement(otherMembers, i));
        if (!equalsNode.execute(selfMemberName, otherMemberName)) {
          return false;
        }
        memberNames[i] = selfMemberName;
      }

      // Check member values
      for (int i = 0; i < membersSize; i++) {
        if (interop.isMemberReadable(selfObject, memberNames[i])
            && interop.isMemberReadable(otherObject, memberNames[i])) {
          Object selfMember =
              valueToEnsoNode.execute(interop.readMember(selfObject, memberNames[i]));
          Object otherMember =
              valueToEnsoNode.execute(interop.readMember(otherObject, memberNames[i]));
          if (!equalsNode.execute(selfMember, otherMember)) {
            return false;
          }
        }
      }
      return true;
    } catch (UnsupportedMessageException
        | InvalidArrayIndexException
        | UnknownIdentifierException e) {
      throw new IllegalStateException(
          String.format(
              "One of the interop objects has probably wrongly specified interop API "
                  + "for members. selfObject = %s ; otherObject = %s",
              selfObject, otherObject),
          e);
    }
  }

  @Specialization(guards = {"isHostObject(selfHostObject)", "isHostObject(otherHostObject)"})
  boolean equalsHostObjects(
      Object selfHostObject,
      Object otherHostObject,
      @CachedLibrary(limit = "5") InteropLibrary interop) {
    try {
      return interop.asBoolean(interop.invokeMember(selfHostObject, "equals", otherHostObject));
    } catch (UnsupportedMessageException
        | ArityException
        | UnknownIdentifierException
        | UnsupportedTypeException e) {
      throw new IllegalStateException(e);
    }
  }

  // HostFunction is identified by a qualified name, it is not a lambda.
  // It has well-defined equality based on the qualified name.
  @Specialization(guards = {"isHostFunction(selfHostFunc)", "isHostFunction(otherHostFunc)"})
  boolean equalsHostFunctions(
      Object selfHostFunc,
      Object otherHostFunc,
      @CachedLibrary(limit = "5") InteropLibrary interop,
      @Cached EqualsNode equalsNode) {
    Object selfFuncStrRepr = interop.toDisplayString(selfHostFunc);
    Object otherFuncStrRepr = interop.toDisplayString(otherHostFunc);
    return equalsNode.execute(selfFuncStrRepr, otherFuncStrRepr);
  }

  @Specialization(guards = "fallbackGuard(left, right, interop, warningsLib)")
  @TruffleBoundary
  boolean equalsGeneric(
      Object left,
      Object right,
      @CachedLibrary(limit = "10") InteropLibrary interop,
      @CachedLibrary(limit = "10") TypesLibrary typesLib,
      @CachedLibrary(limit = "10") WarningsLibrary warningsLib) {
    return left == right
        || interop.isIdentical(left, right, interop)
        || left.equals(right)
        || (isNullOrNothing(left, typesLib, interop) && isNullOrNothing(right, typesLib, interop));
  }

  // We have to manually specify negation of guards of other specializations, because
  // we cannot use @Fallback here. Note that this guard is not precisely the negation of
  // all the other guards on purpose.
  boolean fallbackGuard(
      Object left, Object right, InteropLibrary interop, WarningsLibrary warnings) {
    if (EqualsNode.isPrimitive(left, interop) && EqualsNode.isPrimitive(right, interop)) {
      return false;
    }
    if (isHostObject(left) && isHostObject(right)) {
      return false;
    }
    if (isHostFunction(left) && isHostFunction(right)) {
      return false;
    }
    if (left instanceof Atom && right instanceof Atom) {
      return false;
    }
    if (interop.isNull(left) && interop.isNull(right)) {
      return false;
    }
    if (interop.isString(left) && interop.isString(right)) {
      return false;
    }
    if (interop.hasArrayElements(left) && interop.hasArrayElements(right)) {
      return false;
    }
    if (interop.hasHashEntries(left) && interop.hasHashEntries(right)) {
      return false;
    }
    if (isObjectWithMembers(left, interop) && isObjectWithMembers(right, interop)) {
      return false;
    }
    if (isTimeZone(left, interop) && isTimeZone(right, interop)) {
      return false;
    }
    if (isZonedDateTime(left, interop) && isZonedDateTime(right, interop)) {
      return false;
    }
    if (isDateTime(left, interop) && isDateTime(right, interop)) {
      return false;
    }
    if (isDate(left, interop) && isDate(right, interop)) {
      return false;
    }
    if (isTime(left, interop) && isTime(right, interop)) {
      return false;
    }
    if (interop.isDuration(left) && interop.isDuration(right)) {
      return false;
    }
    if (warnings.hasWarnings(left) || warnings.hasWarnings(right)) {
      return false;
    }
    // For all other cases, fall through to the generic specialization
    return true;
  }

  boolean isTimeZone(Object object, InteropLibrary interop) {
    return !interop.isTime(object) && !interop.isDate(object) && interop.isTimeZone(object);
  }

  boolean isZonedDateTime(Object object, InteropLibrary interop) {
    return interop.isTime(object) && interop.isDate(object) && interop.isTimeZone(object);
  }

  boolean isDateTime(Object object, InteropLibrary interop) {
    return interop.isTime(object) && interop.isDate(object) && !interop.isTimeZone(object);
  }

  boolean isDate(Object object, InteropLibrary interop) {
    return !interop.isTime(object) && interop.isDate(object) && !interop.isTimeZone(object);
  }

  boolean isTime(Object object, InteropLibrary interop) {
    return interop.isTime(object) && !interop.isDate(object) && !interop.isTimeZone(object);
  }

  boolean isObjectWithMembers(Object object, InteropLibrary interop) {
    if (object instanceof Atom) {
      return false;
    }
    if (isHostObject(object)) {
      return false;
    }
    if (interop.isDate(object)) {
      return false;
    }
    if (interop.isTime(object)) {
      return false;
    }
    return interop.hasMembers(object);
  }

  private boolean isNullOrNothing(Object object, TypesLibrary typesLib, InteropLibrary interop) {
    if (typesLib.hasType(object)) {
      return typesLib.getType(object) == EnsoContext.get(this).getNothing();
    } else if (interop.isNull(object)) {
      return true;
    } else {
      return object == null;
    }
  }

  @TruffleBoundary
  boolean isHostObject(Object object) {
    return EnsoContext.get(this).getEnvironment().isHostObject(object);
  }

  @TruffleBoundary
  boolean isHostFunction(Object object) {
    return EnsoContext.get(this).getEnvironment().isHostFunction(object);
  }
}
