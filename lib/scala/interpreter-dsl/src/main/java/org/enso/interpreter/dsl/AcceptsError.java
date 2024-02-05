package org.enso.interpreter.dsl;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** An interface marking an argument as allowing it to accept a DataflowError. */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.SOURCE)
public @interface AcceptsError {}
