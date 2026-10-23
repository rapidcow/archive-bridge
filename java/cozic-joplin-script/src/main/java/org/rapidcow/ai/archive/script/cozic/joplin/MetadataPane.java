package org.rapidcow.ai.archive.script.cozic.joplin;

import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.rapidcow.ai.archive.bridge.cozic.joplin.JoplinEntry;

/**
 * Lists the metadata of an entry, one property per line: the key in
 * bold, then a space and the raw value.  The text can be selected and
 * copied, but not edited.  The note body is never shown.
 */
public final
class
	MetadataPane
extends
	JTextPane
{
	private static final SimpleAttributeSet KEY = new SimpleAttributeSet();
	private static final SimpleAttributeSet VALUE = new SimpleAttributeSet();

	static {
		StyleConstants.setBold(KEY, true);
	}

	public MetadataPane()
	{
		setEditable(false);
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
	}

	/**
	 * Shows the metadata of {@code entry}, or nothing if it is
	 * {@code null}.
	 *
	 * @param entry  the entry to show, or {@code null}
	 */
	void
	show(JoplinEntry entry)
	{
		showProperties(entry == null ? Map.of() : entry.getMetadata());
	}

	/** Shows raw properties, with selectable values and bold keys. */
	public void showProperties(Map<String, String> properties)
	{
		StyledDocument doc = getStyledDocument();
		try {
			doc.remove(0, doc.getLength());
			for (Map.Entry<String, String> property : properties.entrySet()) {
				if (doc.getLength() > 0) {
					doc.insertString(doc.getLength(), "\n", VALUE);
				}
				doc.insertString(doc.getLength(), property.getKey(), KEY);
				doc.insertString(doc.getLength(), " " + property.getValue(), VALUE);
			}
		} catch (BadLocationException e) {
			throw new IllegalStateException(e);
		}
		setCaretPosition(0);
	}
}
