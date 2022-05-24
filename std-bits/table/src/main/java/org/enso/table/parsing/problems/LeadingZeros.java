package org.enso.table.parsing.problems;

import java.util.List;

/** Indicates that some values contained leading zeros when leading zeros where not allowed in the given numeric conversion. */
public record LeadingZeros(String column, List<String> cells) implements ParsingProblem {}
