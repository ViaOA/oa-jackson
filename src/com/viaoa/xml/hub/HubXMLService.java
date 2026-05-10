package com.viaoa.xml.hub;

import java.util.logging.Logger;

import com.viaoa.cascade.OACascade;
import com.viaoa.hub.*;
import com.viaoa.object.OAObject;
import com.viaoa.xml.OAXMLWriter;

public abstract class HubXMLService {
	private final Logger LOG = Logger.getLogger(HubXMLService.class.getName());

	public HubXMLService() {
	}

	/**
	 * Writes the contents of {@code thisHub} to XML using the supplied writer.
	 * Converts the boolean {@code bKeyOnly} flag into the appropriate writer
	 * mode and delegates to the 4-argument {@link #write(Hub, OAXMLWriter, String, int, OACascade)}
	 * method.
	 *
	 * @param thisHub  the Hub whose contents are being serialized
	 * @param ow       XML writer receiving the output
	 * @param tagName  optional tag name to wrap the Hub contents
	 * @param bKeyOnly true to write only object keys, false for full serialization
	 * @param cascade  cascade options controlling nested object serialization
	 */
	public void write(Hub<?> thisHub, OAXMLWriter ow, final String tagName, boolean bKeyOnly, OACascade cascade) {
		write(thisHub, ow, tagName, bKeyOnly ? OAXMLWriter.WRITE_KEYONLY : OAXMLWriter.WRITE_YES, cascade);
	}

	/**
	 * Serializes all objects in the Hub using the given write type. Wraps the
	 * output in the provided tag name if non-null, ensuring proper push/pop
	 * behavior on the writer stack. Delegates actual content generation to
	 * the private {@link #_write(Hub, OAXMLWriter, String, int, OACascade)}.
	 *
	 * @param thisHub   the Hub whose objects are written
	 * @param ow        the XML writer
	 * @param tagName   optional outer tag name
	 * @param writeType configuration flag determining full or key-only output
	 * @param cascade   cascade settings for nested OAObject serialization
	 */
    public void write(Hub<?> thisHub, OAXMLWriter ow, final String tagName, int writeType, OACascade cascade) {
        if (thisHub == null || ow == null) return;
        try {
            if (tagName != null) ow.push(tagName);
            _write(thisHub, ow, tagName, writeType, cascade);
        }
        finally {
            if (tagName != null) ow.pop();
        }
    }
	
    /**
     * Core implementation responsible for generating the XML representation of
     * the Hub. Handles indentation, tag formatting, key-only logic, and
     * per-object serialization via {@link OAObjectXMLDelegate#write}.
     * Also manages pre-scan and removal tracking for objects that will be
     * serialized to avoid redundant output.
     *
     * @param thisHub   the Hub being serialized
     * @param ow        the XML writer producing the output
     * @param tagName   wrapper tag name, or null for default <Hub> tags
     * @param writeType determines full, key-only, or conditional key-only output
     * @param cascade   cascade options governing nested serialization
     */
	private void _write(Hub<?> thisHub, OAXMLWriter ow, final String tagName, int writeType, OACascade cascade) {
	    boolean bKeyOnly = (writeType == OAXMLWriter.WRITE_KEYONLY || writeType == OAXMLWriter.WRITE_NONEW_KEYONLY);
	    ow.indent();
	    
        if (tagName == null) {
            ow.println("<Hub class=\""+ow.getClassName(thisHub.getObjectClass())+"\" total=\""+thisHub.getSize()+"\">");
            // ow.println("<"+ow.getClassName(Hub.class)+" ObjectClass=\""+ow.getClassName(thisHub.getObjectClass())+"\" total=\""+thisHub.getSize()+"\">");
        }
        else {
            ow.println("<"+tagName+" total=\""+thisHub.getSize()+"\">");
        }
	    
	    ow.indent++;

	    for (int i=0; ;i++) {
            Object obj = thisHub.elementAt(i);
            if (obj == null) break;
            if (obj instanceof OAObject) ow.addWillBeWriting((OAObject) obj);
	    }
	    
	    for (int i=0; ;i++) {
	        Object obj = thisHub.elementAt(i);
	        if (obj == null) break;
            if (obj instanceof OAObject) ow.removeWillBeWriting((OAObject) obj);
	        if (writeType == OAXMLWriter.WRITE_NONEW_KEYONLY && obj instanceof OAObject) {
	        	if (((OAObject) obj).getNew()) continue;
	        }
	        String name = thisHub.getObjectClass().getSimpleName();
	        if (obj instanceof OAObject) callObjectXMLWrite((OAObject)obj, ow, name, bKeyOnly, cascade);
	    }
	    ow.indent--;
	    ow.indent();
        if (tagName == null) {
            ow.println("</Hub>");
            // ow.println("</"+ow.getClassName(Hub.class)+">");
        }
        else ow.println("</"+tagName+">");
    }

	public abstract void callObjectXMLWrite(final OAObject oaObj, final OAXMLWriter ow, final String tagName, boolean bKeyOnly, final OACascade cascade);

}
