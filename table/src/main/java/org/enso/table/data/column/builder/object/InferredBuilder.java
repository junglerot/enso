package org.enso.table.data.column.builder.object;

import org.enso.table.data.column.storage.Storage;

/**
 * A builder performing type inference on the appended elements, choosing the best possible storage.
 */
public class InferredBuilder extends Builder {
  private TypedBuilder currentBuilder = null;
  private int currentSize = 0;
  private final int size;

  /**
   * Creates a new instance of this builder, with the given known result size.
   *
   * @param size the result size
   */
  public InferredBuilder(int size) {
    this.size = size;
  }

  @Override
  public void append(Object o) {
    if (currentBuilder == null) {
      if (o == null) {
        currentSize++;
        return;
      } else {
        initBuilderFor(o);
      }
    }
    if (o == null) {
      currentBuilder.append(o);
    } else {
      switch (currentBuilder.getType()) {
        case Storage.Type.BOOL:
          if (o instanceof Boolean) {
            currentBuilder.append(o);
          } else {
            retypeAndAppend(o);
          }
          break;
        case Storage.Type.LONG:
          if (o instanceof Long) {
            currentBuilder.append(o);
          } else {
            retypeAndAppend(o);
          }
          break;
        case Storage.Type.DOUBLE:
          if (o instanceof Double) {
            currentBuilder.append(o);
          } else if (o instanceof Long) {
            currentBuilder.append(((Long) o).doubleValue());
          } else {
            retypeAndAppend(o);
          }
          break;
        case Storage.Type.STRING:
          if (o instanceof String) {
            currentBuilder.append(o);
          } else {
            retypeAndAppend(o);
          }
          break;
        case Storage.Type.OBJECT:
          currentBuilder.append(o);
          break;
      }
    }
    currentSize++;
  }

  private void initBuilderFor(Object o) {
    if (o instanceof Boolean) {
      currentBuilder = new BoolBuilder();
    } else if (o instanceof Double) {
      currentBuilder = NumericBuilder.createDoubleBuilder(size);
    } else if (o instanceof Long) {
      currentBuilder = NumericBuilder.createLongBuilder(size);
    } else if (o instanceof String) {
      currentBuilder = new StringBuilder(size);
    } else {
      currentBuilder = new ObjectBuilder(size);
    }
    for (int i = 0; i < currentSize; i++) {
      currentBuilder.append(null);
    }
  }

  private void retypeAndAppend(Object o) {
    if (o instanceof Double && currentBuilder.canRetypeTo(Storage.Type.DOUBLE)) {
      currentBuilder = currentBuilder.retypeTo(Storage.Type.DOUBLE);
    } else if (o instanceof String && currentBuilder.canRetypeTo(Storage.Type.STRING)) {
      currentBuilder = currentBuilder.retypeTo(Storage.Type.STRING);
    } else if (o instanceof Long && currentBuilder.canRetypeTo(Storage.Type.LONG)) {
      currentBuilder = currentBuilder.retypeTo(Storage.Type.LONG);
    } else if (o instanceof Boolean && currentBuilder.canRetypeTo(Storage.Type.BOOL)) {
      currentBuilder = currentBuilder.retypeTo(Storage.Type.BOOL);
    } else if (currentBuilder.canRetypeTo(Storage.Type.OBJECT)) {
      currentBuilder = currentBuilder.retypeTo(Storage.Type.OBJECT);
    } else {
      retypeToObject();
    }
    currentBuilder.append(o);
  }

  private void retypeToObject() {
    ObjectBuilder objectBuilder = new ObjectBuilder(size);
    currentBuilder.writeTo(objectBuilder.getData());
    objectBuilder.setCurrentSize(currentBuilder.getCurrentSize());
    currentBuilder = objectBuilder;
  }

  @Override
  public int getCurrentSize() {
    return currentSize;
  }

  @Override
  public Storage seal() {
    return currentBuilder.seal();
  }
}
