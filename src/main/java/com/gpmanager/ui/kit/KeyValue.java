package com.gpmanager;
import java.awt.*;
import javax.swing.*;
import static com.gpmanager.Kit.*;
/**
* The key-value table (SIDEBAR_SPEC.md §2): an optional 22 px header, then 20 px lines with the
* key on the left and the value right-aligned in its tone. Hovering a value shows its exact form.
*/
class KeyValue extends JPanel {
final JPanel body = stack(CARD, 0);
KeyValue(String title) {
 super(new BorderLayout());
 setBackground(CARD);
 if (title != null) {
  JLabel header = label(title, small(), LABEL);
  header.setBorder(BorderFactory.createCompoundBorder(
  BorderFactory.createMatteBorder(0, 0, 1, 0, LINE), BorderFactory.createEmptyBorder(0, 6, 0, 6)));
  header.setPreferredSize(new Dimension(0, HEADER));
  add(header, BorderLayout.NORTH);
 }
 add(body, BorderLayout.CENTER);
}

KeyValue clear() {
 body.removeAll();
 revalidate();
 repaint();
 return this;
}

KeyValue put(String key, String value, Kit.Tone tone) {
 return put(key, value, tone, null);
}

/** Adds a line; {@code exact} is the value's hover text, such as "−184,612 gp". */
KeyValue put(String key, String value, Kit.Tone tone, String exact) {
 var line = new JPanel(new BorderLayout(GAP, 0));
 line.setOpaque(false);
 pad(line, 0, 6, 0, 6);
 line.setPreferredSize(new Dimension(0, ROW));
 line.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW));
 // The key keeps its width; a value too long for the rest ends in "…" and hovers in full.
 line.add(label(key, small(), LABEL), BorderLayout.WEST);
 JLabel shown = label(value, body(), (tone == null ? Kit.Tone.PLAIN : tone).color);
 shown.setHorizontalAlignment(JLabel.RIGHT);
 shown.setToolTipText(exact == null ? value : exact);
 line.add(shown, BorderLayout.CENTER);
 body.add(line);
 body.setPreferredSize(new Dimension(0, body.getComponentCount() * ROW));
 revalidate();
 return this;
}

int lines() {
 return body.getComponentCount();
}
}
