package org.enso.base;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Regex_Utils {

  /**
   * Obtains the names for named groups.
   *
   * <p>Assumes that the provided {@link Pattern} is syntactically valid. Behaviour is undefined if
   * run on a syntactically invalid pattern.
   *
   * @param pattern the pattern for which to get the group names
   * @return the names for the named groups in {@code pattern}
   */
  public static String[] get_group_names(Pattern pattern) {
    String pattern_text = pattern.pattern();

    char[] characters = pattern_text.toCharArray();
    ArrayList<String> names = new ArrayList<>();

    for (int i = 0; i < pattern_text.length(); ++i) {
      char character = characters[i];

      if (character == '\\') {
        ++i;
        break;
      }

      String header = "(?<";

      if (pattern_text.startsWith(header, i)) {
        i += header.length();
        StringBuilder buffer = new StringBuilder();

        while (i < pattern_text.length()) {
          character = characters[i];

          if (character == '>') {
            break;
          }

          ++i;

          buffer.append(character);
        }

        names.add(buffer.toString());
      }
    }

    return names.toArray(new String[0]);
  }

  /**
   * Looks for matches of the provided regular expression in the provided text.
   *
   * <p>This should behave exactly the same as `Regex.compile regex . find text` in Enso, it is here
   * only as a temporary workaround, because the Enso function gives wrong results on examples like
   * `Regex.compile "([0-9]+|[^0-9]+)" . find "1a2c"` where it returns `[1, a, 2]` instead of
   * `[1, a, 2, c]`.
   */
  public static String[] find_all_matches(String regex, String text) {
    var allMatches = new ArrayList<String>();
    Matcher m = Pattern.compile(regex).matcher(text);
    while (m.find()) {
      allMatches.add(m.group());
    }
    return allMatches.toArray(new String[0]);
  }
}
