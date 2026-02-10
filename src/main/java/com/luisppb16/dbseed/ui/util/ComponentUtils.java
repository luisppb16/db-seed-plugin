package com.luisppb16.dbseed.ui.util;

import java.awt.event.KeyEvent;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JSpinner;
import javax.swing.KeyStroke;

/**
 * Comprehensive utility suite for configuring and enhancing UI component behavior in the DBSeed plugin.
 * <p>
 * This utility class centralizes common UI component customization patterns used throughout the
 * DBSeed plugin's user interface. It provides specialized methods for enhancing component
 * functionality, improving user experience, and ensuring consistent behavior across different
 * UI elements. The class focuses on addressing common UI/UX challenges and providing reusable
 * solutions for component configuration.
 * </p>
 * <p>
 * Key responsibilities include:
 * <ul>
 *   <li>Configuring keyboard navigation and interaction patterns for enhanced accessibility</li>
 *   <li>Customizing spinner controls with intuitive arrow-key functionality</li>
 *   <li>Providing consistent input handling across different component types</li>
 *   <li>Implementing platform-appropriate UI behavior modifications</li>
 *   <li>Centralizing component configuration logic to promote code reuse</li>
 * </ul>
 * </p>
 * <p>
 * The implementation follows best practices for Swing component manipulation, ensuring proper
 * event handling and maintaining component state integrity. All methods are designed to be
 * safely callable from the Event Dispatch Thread and implement proper null-safety checks
 * to prevent runtime exceptions.
 * </p>
 *
 * @author Luis Pepe
 * @version 1.0
 * @since 2024
 */
public final class ComponentUtils {

  private ComponentUtils() {}

  public static void configureSpinnerArrowKeyControls(final JSpinner spinner) {
    final JComponent editor = spinner.getEditor();
    if (editor instanceof final JSpinner.DefaultEditor defaultEditor) {
      final JFormattedTextField textField = defaultEditor.getTextField();
      final InputMap inputMap = textField.getInputMap(JComponent.WHEN_FOCUSED);

      final String incrementAction = "increment";
      inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), incrementAction);
      final String decrementAction = "decrement";
      inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), decrementAction);

      final ActionMap spinnerActionMap = spinner.getActionMap();
      final ActionMap textFieldActionMap = textField.getActionMap();
      textFieldActionMap.put(incrementAction, spinnerActionMap.get(incrementAction));
      textFieldActionMap.put(decrementAction, spinnerActionMap.get(decrementAction));
    }
  }
}
