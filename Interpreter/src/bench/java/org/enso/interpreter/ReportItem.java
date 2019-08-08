package org.enso.interpreter;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementWrapper;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import java.util.*;
import java.util.stream.DoubleStream;

/** Contains historic results for a single benchmark identified by label. */
@XmlRootElement
public class ReportItem {

  private String label;

  private List<Double> scores;

  public ReportItem() {}

  public ReportItem(String label, List<Double> scores) {
    this.label = label;
    this.scores = scores;
  }

  @XmlElement
  public String getLabel() {
    return label;
  }

  public void setLabel(String label) {
    this.label = label;
  }

  @XmlElementWrapper(name = "scores")
  @XmlElement(name = "score")
  public List<Double> getScores() {
    return scores;
  }

  public void setScores(List<Double> scores) {
    if (scores == null) scores = new ArrayList<>();
    this.scores = scores;
  }

  /**
   * Registers a new score for this item.
   * @param score Score to register.
   */
  public void addScore(double score) {
    getScores().add(score);
  }

  /**
   * @return The best (lowest) historic result for this benchmark.
   */
  @XmlTransient
  public Optional<Double> getBestScore() {
    OptionalDouble min = getScores().stream().mapToDouble(s -> s).min();
    return min.isPresent() ? Optional.of(min.getAsDouble()) : Optional.empty();
  }
}
