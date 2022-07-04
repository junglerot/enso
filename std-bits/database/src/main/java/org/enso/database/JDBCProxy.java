package org.enso.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * A helper class for accessing the JDBC components.
 *
 * <p>This class is necessary because the JDBC depends on the caller's classloader to determine
 * which drivers are available and so if it is called directly from Enso it does not see the correct
 * classloaders, thus not detecting the proper drivers.
 */
public class JDBCProxy {
  /**
   * A helper method that lists registered JDBC drivers.
   *
   * <p>Can be used for debugging.
   *
   * @return an array of JDBC drivers that are currently registered
   */
  public static Object[] getDrivers() {
    return DriverManager.drivers().toArray();
  }

  /**
   * Tries to create a new connection using the JDBC DriverManager.
   *
   * <p>It delegates directly to {@code DriverManager.getConnection}. That is needed because if that
   * method is called directly from Enso, the JDBC drivers are not detected correctly.
   *
   * @param url database url to connect to, starting with `jdbc:`
   * @param properties configuration for the connection
   * @return a connection
   */
  public static Connection getConnection(String url, Properties properties) throws SQLException {
    return DriverManager.getConnection(url, properties);
  }

  public static String[] getStringColumn(ResultSet resultSet, String column) throws SQLException {
    if (resultSet.isClosed()) {
      return new String[0];
    }

    int colIndex = resultSet.findColumn(column);
    List<String> values = new ArrayList<>();
    while (resultSet.next()) {
      values.add(resultSet.getString(colIndex));
    }
    return values.toArray(String[]::new);
  }
}
