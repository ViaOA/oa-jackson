/*
 * Copyright 1999–2025 ViaOA (info@viaoa.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.viaoa.json;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.type.MapType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.viaoa.cascade.OACascade;
import com.viaoa.converter.OAConv;
import com.viaoa.datasource.OADataSource;
import com.viaoa.datetime.OADate;
import com.viaoa.graph.OAGraph;
import com.viaoa.graph.OAGraphInternal;
import com.viaoa.graph.service.object.OAObjectCacheService;
import com.viaoa.graph.service.object.OAObjectInfoService;
import com.viaoa.graph.service.object.OAObjectImportMatchService.ImportMatch;
import com.viaoa.hub.Hub;
import com.viaoa.json.jackson.OAJacksonModule;
import com.viaoa.lang.OAString;
import com.viaoa.metadata.OALinkInfo;
import com.viaoa.metadata.OAObjectInfo;
import com.viaoa.metadata.OAPropertyInfo;
import com.viaoa.object.OAObject;
import com.viaoa.object.OAObjectKey;
import com.viaoa.runtime.OARuntime;
import com.viaoa.runtime.OAThreadLocalService;
import com.viaoa.runtime.OAThreadService;

/**
 * Provides JSON serialization and deserialization for OAObjects, Hubs, method
 * arguments, and general Java types using Jackson as the underlying engine.
 * <p>
 * OAJson supports the full OAObjectGraph model: object identity, GUID-based
 * resolution, ImportMatch logic, cascading rules, property-path inclusion,
 * Hub relationships, and lazy-loading behavior. It is the primary mechanism
 * for converting OA objects to/from external formats for REST, remote-method
 * calls, distributed messaging, and persistence utilities.
 * </p>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li><b>Object identity resolution</b> – uses primary keys, OAObjectKey,
 *       and GUIDs to reattach to existing instances or create new ones.</li>
 *   <li><b>Property-path based filtering</b> – serialize specific paths using
 *       OAPropertyPath expressions, supporting deep selection or selective
 *       field inclusion.</li>
 *   <li><b>Cascade-aware serialization</b> – respects OACascade settings to
 *       include or omit references, owned objects, or dependent Hubs.</li>
 *   <li><b>Hub serialization</b> – supports lists of objects, Hub metadata,
 *       and Hub-to-object relationships.</li>
 *   <li><b>ImportMatch support</b> – performs identity matching for POJO inputs
 *       or external JSON where primary keys are not available.</li>
 *   <li><b>Remote-method argument handling</b> – converts arbitrary argument
 *       arrays into JSON, including polymorphic hints so the receiver can
 *       reconstruct Java types.</li>
 *   <li><b>POJO mode</b> – allows non-OA types to be used during import or
 *       object creation workflows, such as REST endpoints or CSV/JSON loaders.</li>
 *   <li><b>Pretty printing</b> – optional formatted JSON for debugging.</li>
 * </ul>
 *
 * <h2>Deserializer Behavior</h2>
 * <p>
 * Deserialization first examines the incoming JSON node to determine whether
 * an existing OAObject can be reused. Identity can be determined through:
 * </p>
 * <ul>
 *   <li>primary key value (single or multipart),</li>
 *   <li>a GUID value ("guid.xxx"),</li>
 *   <li>ImportMatch logic when keys are missing or inconsistent.</li>
 * </ul>
 * <p>
 * Hubs deserialize arrays of objects or IDs and will reattach objects to the
 * correct Hub instance, preserving identity and ordering.
 * </p>
 *
 * <h2>Thread-Local Integration</h2>
 * <ul>
 *   <li>Marks OAThreadLocalDelegate state during load operations.</li>
 *   <li>Temporarily installs a Jackson mapper into thread-local storage so
 *       OAObjectSerializer/OAObjectDeserializer behave consistently.</li>
 *   <li>All instance state is non-static; the class is not thread-safe but
 *       safe to use concurrently when each thread owns its own OAJson instance.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 *   OAJson json = new OAJson();
 *   String s = json.write(myObject);
 *   MyClass x = json.readObject(s, MyClass.class);
 * </pre>
 *
 * <h2>Design Notes</h2>
 * <ul>
 *   <li>This class acts as a façade over Jackson, providing OA’s domain-
 *       specific identity and reference rules.</li>
 *   <li>The logic mirrors OAObjectGraph traversal rules to generate valid,
 *       loadable JSON output.</li>
 *   <li>No reflection is used to navigate OAObjects; all access occurs through
 *       OAPropertyPath and OAObjectDelegates.</li>
 * </ul>
 *
 * @author vvia
 */
public class OAJson {
	/**
	 * Lazily initialized, shared {@link ObjectMapper} instance used as the default
	 * JSON engine for all OAJson instances when a dedicated mapper is not required.
	 */
	private static volatile ObjectMapper jsonObjectMapper;
	
	/**
	 * Synchronization lock used when initializing the shared {@link #jsonObjectMapper}
	 * to ensure thread-safe lazy creation.
	 */
	protected static final Object lock = new Object();

	/**
	 * Instance-level {@link ObjectMapper} used by this OAJson. Defaults to the
	 * shared mapper but can be replaced with an unshared mapper for custom settings.
	 */
	protected ObjectMapper objectMapper;
	
	/**
	 * Collection of property-path expressions that restrict which properties and
	 * references are included when writing OAObjects to JSON.
	 */
	private final ArrayList<String> alPropertyPath = new ArrayList<>();

	/**
	 * Flag indicating whether owned links for root objects should be included in
	 * serialized JSON. Defaults to {@code true}.
	 */
	private boolean bIncludeOwned = true;
	
	/**
	 * Flag indicating whether all references should be included when writing
	 * JSON, subject to reference reuse and circular-reference protection.
	 */
	private boolean bIncludeAll;

	/**
	 * List of {@link ImportMatch} rules used during import or read operations to
	 * match incoming JSON objects to existing OAObject instances when keys are
	 * missing or incomplete.
	 */
	private List<ImportMatch> alImportMatch = new ArrayList<>();

	/**
	 * Returns the list of {@link ImportMatch} rules used to align incoming JSON
	 * data with existing OAObjects. The list is lazily initialized if needed.
	 *
	 * @return mutable list of {@link ImportMatch} definitions
	 */
	public List<ImportMatch> getImportMatchList() {
		if (alImportMatch == null) {
			alImportMatch = new ArrayList<>();
		}
		return alImportMatch;
	}

	/**
	 * Flag indicating whether JSON should be written in POJO-compatible form,
	 * including additional properties that are not required by OAObjects but are
	 * useful for external import scenarios.
	 */
	private boolean bWriteAsPojo;

	/**
	 * Reference to the root OAObject used during {@code readIntoObject} calls so
	 * that deserialization can apply updates to an existing object graph.
	 */
	private OAObject root;

	/**
	 * Holds the target class for the current read operation, used to determine
	 * how OAObjects and related structures should be constructed or matched.
	 */
	private Class readObjectClass;
	
	/**
	 * Current {@link StackItem} representing the active position in the object
	 * graph while reading or writing, used for tracking context such as links
	 * and property paths.
	 */
	private StackItem stackItem;

	/**
	 * Flag indicating whether the JSON currently being processed represents POJO
	 * data rather than full OAObject structures.
	 */
	private boolean bReadingPojo;

	/**
	 * Sets whether the JSON being read should be treated as originating from a
	 * POJO source instead of a full OAObject representation.
	 *
	 * @param b {@code true} if reading POJO-style JSON, {@code false} otherwise
	 */
	public void setReadingPojo(boolean b) {
		this.bReadingPojo = b;
	}

	/**
	 * Returns whether the current read operation is treating the JSON input as
	 * POJO data.
	 *
	 * @return {@code true} if POJO mode is active, otherwise {@code false}
	 */
	public boolean isReadingPojo() {
		return bReadingPojo;
	}

	/**
	 * Alias for {@link #isReadingPojo()}, provided for frameworks or callers that
	 * expect a {@code getXxx} style accessor.
	 *
	 * @return {@code true} if POJO mode is active, otherwise {@code false}
	 */
	public boolean getReadingPojo() {
		return bReadingPojo;
	}

	/**
	 * Map used during read operations to resolve GUID-based references back to
	 * OAObject instances, allowing refId entries in JSON to be reattached to the
	 * correct objects.
	 */
	private Map<UUID, OAObject> hmGuidObject;

	/**
	 * Cascade state used when writing OAObjects so that reference reuse, duplicate
	 * suppression, and circular-reference handling follow OA cascade rules.
	 */
	private OACascade cascade;

	/**
	 * Enables or disables inclusion of owned references for root objects during
	 * JSON serialization.
	 *
	 * @param b {@code true} to include owned references, {@code false} to omit them
	 */
	public void setIncludeOwned(boolean b) {
		bIncludeOwned = b;
	}

	/**
	 * Returns whether owned references for root objects are included during
	 * JSON serialization.
	 *
	 * @return {@code true} if owned references are included, otherwise {@code false}
	 */
	public boolean getIncludeOwned() {
		return bIncludeOwned;
	}

	/**
	 * Enables or disables inclusion of all references when writing JSON.
	 * Reference reuse rules still apply to prevent duplication or recursion.
	 *
	 * @param b {@code true} to include all references, {@code false} to limit output
	 */
	public void setIncludeAll(boolean b) {
		bIncludeAll = b;
	}

	/**
	 * Returns whether all references are included during JSON serialization.
	 *
	 * @return {@code true} if all references should be included
	 */
	public boolean getIncludeAll() {
		return bIncludeAll;
	}

	/**
	 * Resets internal state before a read operation. Clears ImportMatch data,
	 * stack state, and cascade information.
	 */
	protected void reset() {
		if (alImportMatch != null) {
			alImportMatch.clear();
		}
		setStackItem(null);
		cascade = null;
	}

	/**
	 * Represents a node within the object graph traversal stack during read or
	 * write operations. Tracks object identity, property context, and JSON node
	 * information.
	 */
	public static class StackItem {
		/**
		 * Creates an empty {@link StackItem} representing a new node in the
		 * traversal hierarchy.
		 */
		public StackItem() {
		}

		/**
		 * Parent stack item in the traversal hierarchy, representing the link from
		 * which this child node originates.
		 */
		public StackItem parent;

		/**
		 * Metadata describing the OAObject type represented by this stack item.
		 */
		public OAObjectInfo oi;

		/**
		 * Metadata describing the link from the parent OAObject to this child
		 * object, used to determine property-path context.
		 */
		public OALinkInfo li; // from parent to child

		/**
		 * The JSON node corresponding to this position in the traversal, used
		 * during read operations to populate the OAObject.
		 */
		public JsonNode node;

		/**
		 * The OAObject instance associated with this traversal point. May represent
		 * an existing or newly created object depending on JSON identity information.
		 */
		public OAObject obj;
		
		/**
		 * The object key representing identity discovered in the JSON node, used
		 * to match or create OAObject instances.
		 */
		public OAObjectKey key;

		/**
		 * Returns a dotted property-path style representation of this stack item's
		 * position in the traversal hierarchy, composed from parent and link names.
		 *
		 * @return textual representation of the traversal path
		 */
		public String toString() {
			String s;
			if (parent == null) {
				s = oi.getForClass().getSimpleName();
				if (li != null) {
					s += ":" + li.getName();
				}
			} else {
				s = parent.toString();
				if (li != null) {
					s += "." + li.getName();
				}
			}
			return s;
		}
	}

	/**
	 * Returns the class type of the root OAObject being read during the current
	 * deserialization operation.
	 *
	 * @return the OAObject class that is the root target for reading
	 */
	public Class<? extends OAObject> getReadObjectClass() {
		return readObjectClass;
	}

	/**
	 * Adds a property-path expression to the list of paths that define which
	 * references and properties are included during JSON serialization.
	 *
	 * @param propertyPath the property path to include
	 */
	public void addPropertyPath(String propertyPath) {
		if (propertyPath != null) {
			alPropertyPath.add(propertyPath);
		}
	}

	/**
	 * Adds multiple property-path expressions to the list of paths that define
	 * which references and properties are included during JSON serialization.
	 *
	 * @param pps list of property-path expressions to include
	 */
	public void addPropertyPaths(List<String> pps) {
		if (pps != null) {
			for (String pp : pps) {
				alPropertyPath.add(pp);
			}
		}
	}

	/**
	 * Returns the list of property-path expressions currently configured for
	 * JSON serialization.
	 *
	 * @return list of property-paths to include
	 */
	public ArrayList<String> getPropertyPaths() {
		return alPropertyPath;
	}

	/**
	 * Clears all property-path expressions previously added for JSON
	 * serialization.
	 */
	public void clearPropertyPaths() {
		alPropertyPath.clear();
	}


	/**
	 * Returns the lazily initialized shared {@link ObjectMapper}. If the mapper
	 * does not yet exist, it is created under synchronization.
	 *
	 * @return shared ObjectMapper instance
	 */
	public static ObjectMapper getJsonObjectMapper() {
		if (jsonObjectMapper == null) {
			synchronized (lock) {
				if (jsonObjectMapper == null) {
					jsonObjectMapper = createJsonObjectMapper();					
				}
			}
		}
		return jsonObjectMapper;
	}

	/**
	 * Creates and configures a new {@link ObjectMapper} with OA-specific Jackson
	 * modules, date/time settings, comment support, and pretty-printing enabled.
	 *
	 * @return newly configured ObjectMapper
	 */
	public static ObjectMapper createJsonObjectMapper() {
		ObjectMapper objectMapperx = new ObjectMapper();
		objectMapperx.registerModule(new JavaTimeModule());
		objectMapperx.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		objectMapperx.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
		objectMapperx.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		
		objectMapperx.enable(JsonParser.Feature.ALLOW_COMMENTS);
        objectMapperx.enable(JsonParser.Feature.ALLOW_YAML_COMMENTS);
		
		objectMapperx.setDefaultPropertyInclusion(Include.ALWAYS);
		// objectMapperx.setSerializationInclusion(Include.NON_NULL);

		objectMapperx.registerModule(new OAJacksonModule());
		objectMapperx.enable(SerializationFeature.INDENT_OUTPUT);
					
		return objectMapperx;
	}
	
	/**
	 * Returns this OAJson instance’s {@link ObjectMapper}. If none has been
	 * assigned yet, the shared mapper is obtained and stored.
	 *
	 * @return the ObjectMapper used for JSON operations
	 */
	public ObjectMapper getObjectMapper() {
		if (objectMapper == null) {
			objectMapper = getJsonObjectMapper();
		}
		return objectMapper;
	}

	/**
	 * Creates and assigns a new, unshared {@link ObjectMapper} instance for this
	 * OAJson object. Useful when custom case-sensitivity or other settings must
	 * not affect the shared mapper.
	 *
	 * @return a newly created ObjectMapper dedicated to this OAJson
	 */
	public ObjectMapper getUnsharedObjectMapper() {
		objectMapper = createJsonObjectMapper();
		return objectMapper;
	}
	
	/**
	 * Configures this OAJson instance's unshared {@link ObjectMapper} to treat
	 * JSON properties as case-insensitive during deserialization.
	 */
	public void setCaseInsensitive() {
		getUnsharedObjectMapper().configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
	}
	
	
	/**
	 * Convenience wrapper for {@link #write(Object)} that converts the given
	 * object into a JSON string using default pretty-printing settings.
	 *
	 * @param obj the object to serialize
	 * @return formatted JSON representation
	 * @throws JsonProcessingException if serialization fails
	 */
	public String toJson(Object obj) throws JsonProcessingException {
		return write(obj);
	}
	
	/**
	 * Serializes an object to JSON using the configured {@link ObjectMapper},
	 * including owned links and any paths defined in {@link #alPropertyPath}.
	 * Thread-local Jackson integration is applied for OA-aware serializers.
	 *
	 * @param obj the object to serialize
	 * @return the JSON string
	 * @throws JsonProcessingException if serialization fails
	 */
	public String write(Object obj) throws JsonProcessingException {
		setStackItem(null);
		this.cascade = null;
		String json;
		json = getObjectMapper().writerWithDefaultPrettyPrinter().withAttribute("oajson", this).writeValueAsString(obj);

		return json;
	}

	/**
	 * Convenience wrapper for {@link #format(String)} that re-formats an existing
	 * JSON string using pretty-print indentation.
	 *
	 * @param json raw or compact JSON text
	 * @return formatted JSON
	 * @throws JsonProcessingException if parsing fails
	 */
	public String convertToPretty(String json) throws JsonProcessingException {
		return format(json);
	}

	/**
	 * Parses the supplied JSON string and re-serializes it using pretty-printing.
	 *
	 * @param json the JSON text to format
	 * @return pretty-printed JSON
	 * @throws JsonProcessingException if parsing fails
	 */
	public String format(String json) throws JsonProcessingException {
		ObjectMapper mapper = getObjectMapper();
		Object jsonObject = mapper.readValue(json, Object.class);
		String prettyJson = mapper.writerWithDefaultPrettyPrinter().withAttribute("oajson", this).writeValueAsString(jsonObject);
		return prettyJson;
	}

	/**
	 * Writes the JSON serialization of the supplied object directly to the given
	 * file, using default pretty-printing. OA-specific thread-local flags are
	 * applied during the write operation.
	 *
	 * @param obj  the object to serialize
	 * @param file destination file
	 * @throws JsonProcessingException if serialization fails
	 * @throws IOException if file I/O fails
	 */
	public void write(Object obj, File file) throws JsonProcessingException, IOException {
		setStackItem(null);
		this.cascade = null;
		getObjectMapper().writerWithDefaultPrettyPrinter().withAttribute("oajson", this).writeValue(file, obj);
	}

	/**
	 * Writes the JSON serialization of the supplied object to the target output
	 * stream, using default pretty-print formatting. OA-aware serializers are
	 * enabled via thread-local state during the write.
	 *
	 * @param obj    the object to serialize
	 * @param stream output stream to receive JSON data
	 * @throws JsonProcessingException if serialization fails
	 * @throws IOException if writing to the stream fails
	 */
	public void write(Object obj, final OutputStream stream) throws JsonProcessingException, IOException {
		setStackItem(null);
		this.cascade = null;
		String json;
		getObjectMapper().writerWithDefaultPrettyPrinter().withAttribute("oajson", this).writeValue(stream, obj);
	}

	/**
	 * Reads a JSON string into a Java object of the given class. If the class
	 * represents an {@link OAObject}, identity matching logic is applied so
	 * that existing objects may be reused.
	 *
	 * @param json  JSON text to read
	 * @param clazz target class to deserialize
	 * @return deserialized object instance
	 * @throws JsonProcessingException if deserialization fails
	 */
	public <T> T readObject(final String json, final Class<T> clazz) throws JsonProcessingException {
		T t = readObject(json, clazz, false);
		return t;
	}

	/**
	 * Returns the current {@link StackItem} used to track context during JSON
	 * read/write operations.
	 *
	 * @return active StackItem or {@code null} if none
	 */
	public StackItem getStackItem() {
		return stackItem;
	}

	/**
	 * Assigns the active {@link StackItem} for use during nested read/write
	 * operations.
	 *
	 * @param si the stack item to set
	 */
	public void setStackItem(StackItem si) {
		this.stackItem = si;
	}

	/**
	 * Returns the OAObject currently designated as the root object for an active
	 * read-into-object operation.
	 *
	 * @return the root OAObject, or {@code null} if none
	 */
	public OAObject getRoot() {
		return this.root;
	}

	/**
	 * Reads the supplied JSON text into an existing OAObject instance, updating
	 * its properties and references while preserving identity.
	 *
	 * @param json JSON text to read
	 * @param root target OAObject to populate
	 * @throws JsonProcessingException if deserialization fails
	 */
	public void readIntoObject(final String json, OAObject root) throws JsonProcessingException {
		readIntoObject(json, root, false);
	}

	/**
	 * Reads JSON into an existing OAObject while optionally enabling loading
	 * mode, which affects thread-local behavior during deserialization.
	 *
	 * @param json       JSON input
	 * @param root       target OAObject
	 * @param bIsLoading whether loading mode should be enabled
	 * @throws JsonProcessingException if parsing fails
	 */
	public void readIntoObject(final String json, OAObject root, final boolean bIsLoading) throws JsonProcessingException {
		if (root == null) {
			return;
		}
		this.root = root;
		readObject(json, root.getClass(), bIsLoading);
		this.root = null;
	}

	/**
	 * Reads JSON from an input stream into an existing OAObject, applying the
	 * same identity and update semantics used when reading from a string.
	 *
	 * @param is   JSON input stream
	 * @param root OAObject to update
	 * @throws JsonProcessingException if parsing fails
	 * @throws IOException if stream access fails
	 */
	public void readIntoObject(final InputStream is, OAObject root) throws JsonProcessingException, IOException {
		readIntoObject(is, root, false);
	}

	/**
	 * Reads JSON from an input stream into an existing OAObject while optionally
	 * enabling loading mode for thread-local handling.
	 *
	 * @param is         JSON input stream
	 * @param root       OAObject to update
	 * @param bIsLoading whether loading mode is active
	 * @throws JsonProcessingException if JSON parsing fails
	 * @throws IOException if stream I/O fails
	 */
	public void readIntoObject(final InputStream is, OAObject root, final boolean bIsLoading) throws JsonProcessingException, IOException {
		if (root == null) {
			return;
		}
		this.root = root;
		readObject(is, root.getClass(), bIsLoading);
		this.root = null;
	}

	/**
	 * Core JSON-to-object deserialization routine. Initializes identity maps,
	 * configures thread-local state, selects the correct type for OAObject
	 * deserialization, and performs the read operation.
	 *
	 * @param json       JSON input
	 * @param clazz      target class to deserialize
	 * @param bIsLoading whether loading mode should be applied
	 * @return deserialized object instance
	 * @throws JsonProcessingException if parsing fails
	 */
	public <T> T readObject(final String json, final Class<T> clazz, final boolean bIsLoading)
			throws JsonProcessingException {
		reset();
		this.readObjectClass = clazz;
		ObjectMapper om = getObjectMapper();

		hmGuidObject = null;
		getGuidMap();

		T obj;
		final OAThreadLocalService srvcOAThreadLocal = ((OAThreadService) OARuntime.thread()).getThreadLocalService();
		boolean bWas = false;
		try {
			if (bIsLoading) {
				bWas = srvcOAThreadLocal.setLoading(true);
			} else {
				bWas = srvcOAThreadLocal.getSendSyncMessages();
				srvcOAThreadLocal.setSendSyncMessages(false);
			}

			Class c = clazz;
			if (OAObject.class.isAssignableFrom(clazz)) {
				c = OAObject.class;
			}
			JavaType jt = om.getTypeFactory().constructType(c);

			obj = om.readerFor(jt).withAttribute("oajson", this).readValue(json);
			//qqqqq was: obj = (T) om.readValue(json, jt);

		} finally {
			if (bIsLoading) {
				srvcOAThreadLocal.setLoading(bWas);
			} else {
				srvcOAThreadLocal.setSendSyncMessages(bWas);
			}
			readObjectClass = null;
		}

		return obj;
	}

	/**
	 * Callback hook invoked after a JSON read completes. Subclasses can override
	 * to perform post-processing steps.
	 */
	protected void afterReadJson() {
	}

	/**
	 * Reads a JSON stream into a Java object of the given class, applying the
	 * same identity, mapper, and thread-local semantics as the string-based
	 * version of {@code readObject}.
	 *
	 * @param stream     JSON input stream
	 * @param clazz      class to deserialize
	 * @param bIsLoading whether loading mode is active
	 * @return deserialized object instance
	 * @throws JsonProcessingException if parsing fails
	 * @throws IOException if stream I/O fails
	 */
	public <T> T readObject(final InputStream stream, final Class<T> clazz, final boolean bIsLoading)
			throws JsonProcessingException, IOException {
		reset();
		this.readObjectClass = clazz;
		ObjectMapper om = getObjectMapper();
		setStackItem(null);
		cascade = null;

		hmGuidObject = null;
		// Map<UUID, OAObject> hmGuidMap = getGuidMap();

		T obj;
		final OAThreadLocalService srvcOAThreadLocal = ((OAThreadService) OARuntime.thread()).getThreadLocalService();
		boolean bWas = false;
		try {
			if (bIsLoading) {
				bWas = srvcOAThreadLocal.setLoading(true);
			} else {
				bWas = srvcOAThreadLocal.getSendSyncMessages();
				srvcOAThreadLocal.setSendSyncMessages(false);
			}

			Class c = clazz;
			if (OAObject.class.isAssignableFrom(clazz)) {
				c = OAObject.class;
			}
			JavaType jt = om.getTypeFactory().constructType(c);
			obj = om.readerFor(jt).withAttribute("oajson", this).readValue(stream);
			//qqqqqqqq was: obj = (T) om.readValue(stream, jt);

		} finally {
			if (bIsLoading) {
				srvcOAThreadLocal.setLoading(bWas);
			} else {
				srvcOAThreadLocal.setSendSyncMessages(bWas);
			}
			readObjectClass = null;
		}

		return obj;
	}

	/**
	 * Reads a JSON file into a Java object of the specified class. Applies
	 * OAObject identity resolution, thread-local Jackson configuration, and
	 * loading/sync flags as needed. Supports OAObject and non-OA types.
	 *
	 * @param file       JSON input file
	 * @param clazz      target class for deserialization
	 * @param bIsLoading whether loading mode is active
	 * @return deserialized object instance
	 * @throws JsonProcessingException if JSON parsing fails
	 * @throws IOException if file access fails
	 */
	public <T> T readObject(final File file, final Class<T> clazz, final boolean bIsLoading)
			throws JsonProcessingException, IOException {
		reset();
		this.readObjectClass = clazz;
		ObjectMapper om = getObjectMapper();

		hmGuidObject = null;
		// Map<UUID, OAObject> hmGuidMap = getGuidMap();

		T obj;
		final OAThreadLocalService srvcOAThreadLocal = ((OAThreadService) OARuntime.thread()).getThreadLocalService();
		boolean bWas = false;
		try {
			if (bIsLoading) {
				bWas = srvcOAThreadLocal.setLoading(true);
			} else {
				bWas = srvcOAThreadLocal.getSendSyncMessages();
				srvcOAThreadLocal.setSendSyncMessages(false);
			}

			Class c = clazz;
			if (OAObject.class.isAssignableFrom(clazz)) {
				c = OAObject.class;
			}
			JavaType jt = om.getTypeFactory().constructType(c);
			obj = om.readerFor(jt).withAttribute("oajson", this).readValue(file);
			//qqqqq was: obj = (T) om.readValue(file, jt);

		} finally {
			if (bIsLoading) {
				srvcOAThreadLocal.setLoading(bWas);
			} else {
				srvcOAThreadLocal.setSendSyncMessages(bWas);
			}
			readObjectClass = null;
		}

		return obj;
	}

	/**
	 * Reads a JSON string into a {@link Map} with the specified key and value
	 * types. If the value type is an OAObject, identity matching rules are used.
	 * Thread-local Jackson handling is applied.
	 *
	 * @param json       JSON input text
	 * @param clazzKey   key type
	 * @param clazzValue value type
	 * @param bIsLoading whether loading mode is active
	 * @return deserialized map instance
	 * @throws JsonProcessingException if parsing fails
	 * @throws IOException if reading fails
	 */
	public <K, V> Map<K, V> readMap(final String json, final Class<K> clazzKey, final Class<V> clazzValue,
			final boolean bIsLoading)
			throws JsonProcessingException, IOException {
		reset();
		this.readObjectClass = clazzValue;
		ObjectMapper om = getObjectMapper();
		hmGuidObject = null;

		Map<K, V> map;
		final OAThreadLocalService srvcOAThreadLocal = ((OAThreadService) OARuntime.thread()).getThreadLocalService();
		boolean bWas = false;
		try {
			if (bIsLoading) {
				bWas = srvcOAThreadLocal.setLoading(true);
			} else {
				bWas = srvcOAThreadLocal.getSendSyncMessages();
				srvcOAThreadLocal.setSendSyncMessages(false);
			}

			Class c = clazzValue;
			if (OAObject.class.isAssignableFrom(clazzValue)) {
				c = OAObject.class;
			}

			MapType mt = om.getTypeFactory().constructMapType(Map.class, clazzKey, c);

			map = om.readerFor(mt).withAttribute("oajson", this).readValue(json);

			//qqqqqqqqq was: map = (Map<K, V>) om.readValue(json, mt);
		} finally {
			if (bIsLoading) {
				srvcOAThreadLocal.setLoading(bWas);
			} else {
				srvcOAThreadLocal.setSendSyncMessages(bWas);
			}
			readObjectClass = null;
		}

		return map;
	}

	/**
	 * Reads a JSON string into a {@link List} of objects of the given element
	 * type. OAObject values are handled with identity resolution logic.
	 *
	 * @param json       JSON input
	 * @param clazz      element type
	 * @param bIsLoading whether loading mode is active
	 * @return deserialized list
	 * @throws JsonProcessingException if parsing fails
	 * @throws IOException if reading fails
	 */
	public <T> List<T> readList(final String json, final Class<T> clazz, final boolean bIsLoading)
			throws JsonProcessingException, IOException {
		reset();
		this.readObjectClass = clazz;
		ObjectMapper om = getObjectMapper();
		hmGuidObject = null;

		List<T> list;
		final OAThreadLocalService srvcOAThreadLocal = ((OAThreadService) OARuntime.thread()).getThreadLocalService();
		boolean bWas = false;
		try {
			if (bIsLoading) {
				bWas = srvcOAThreadLocal.setLoading(true);
			} else {
				bWas = srvcOAThreadLocal.getSendSyncMessages();
				srvcOAThreadLocal.setSendSyncMessages(false);
			}

			Class c = clazz;
			if (OAObject.class.isAssignableFrom(clazz)) {
				c = OAObject.class;
			}
			CollectionType ct = om.getTypeFactory().constructCollectionType(List.class, c);
			list = om.readerFor(ct).withAttribute("oajson", this).readValue(json);
			//was qqqqqqq list = (List<T>) om.readValue(json, ct);

		} finally {
			if (bIsLoading) {
				srvcOAThreadLocal.setLoading(bWas);
			} else {
				srvcOAThreadLocal.setSendSyncMessages(bWas);
			}
			readObjectClass = null;
		}

		return list;
	}

	/**
	 * Reads a JSON file into a {@link List} of objects of the given type.
	 * Supports OAObject identity handling and thread-local Jackson integration.
	 *
	 * @param file       JSON file
	 * @param clazz      list element type
	 * @param bIsLoading whether loading mode is active
	 * @return deserialized list
	 * @throws JsonProcessingException if parsing fails
	 * @throws IOException if reading fails
	 */
	public <T> List<T> readList(final File file, final Class<T> clazz, final boolean bIsLoading)
			throws JsonProcessingException, IOException {
		reset();
		this.readObjectClass = clazz;
		ObjectMapper om = getObjectMapper();
		hmGuidObject = null;

		List<T> list;
		final OAThreadLocalService srvcOAThreadLocal = ((OAThreadService) OARuntime.thread()).getThreadLocalService();
		boolean bWas = false;
		try {
			if (bIsLoading) {
				bWas = srvcOAThreadLocal.setLoading(true);
			} else {
				bWas = srvcOAThreadLocal.getSendSyncMessages();
				srvcOAThreadLocal.setSendSyncMessages(false);
			}

			Class c = clazz;
			if (OAObject.class.isAssignableFrom(clazz)) {
				c = OAObject.class;
			}
			CollectionType ct = om.getTypeFactory().constructCollectionType(List.class, c);
			list = om.readerFor(ct).withAttribute("oajson", this).readValue(file);

			//was qqqqqqqqq list = (List<T>) om.readValue(file, ct);
		} finally {
			if (bIsLoading) {
				srvcOAThreadLocal.setLoading(bWas);
			} else {
				srvcOAThreadLocal.setSendSyncMessages(bWas);
			}
			readObjectClass = null;
		}

		return list;
	}

	/**
	 * Reads a JSON stream into a {@link List} of objects of the given type.
	 * Supports OAObject deserialization behavior and identity matching.
	 *
	 * @param stream     JSON input stream
	 * @param clazz      list element type
	 * @param bIsLoading whether loading mode is active
	 * @return deserialized list
	 * @throws JsonProcessingException if parsing fails
	 * @throws IOException if stream access fails
	 */
	public <T> List<T> readList(final InputStream stream, final Class<T> clazz, final boolean bIsLoading)
			throws JsonProcessingException, IOException {
		reset();
		this.readObjectClass = clazz;
		ObjectMapper om = getObjectMapper();
		hmGuidObject = null;

		List<T> list;
		final OAThreadLocalService srvcOAThreadLocal = ((OAThreadService) OARuntime.thread()).getThreadLocalService();
		boolean bWas = false;
		try {
			if (bIsLoading) {
				bWas = srvcOAThreadLocal.setLoading(true);
			} else {
				bWas = srvcOAThreadLocal.getSendSyncMessages();
				srvcOAThreadLocal.setSendSyncMessages(false);
			}

			Class c = clazz;
			if (OAObject.class.isAssignableFrom(clazz)) {
				c = OAObject.class;
			}
			CollectionType ct = om.getTypeFactory().constructCollectionType(List.class, c);
			list = om.readerFor(ct).withAttribute("oajson", this).readValue(stream);

			//qqqqqqqqqq was: list = (List<T>) om.readValue(stream, ct);
		} finally {
			if (bIsLoading) {
				srvcOAThreadLocal.setLoading(bWas);
			} else {
				srvcOAThreadLocal.setSendSyncMessages(bWas);
			}
			readObjectClass = null;
		}

		return list;
	}

	/**
	 * Returns the current {@link OACascade} instance used to track reference
	 * reuse and duplication control during write operations. Lazily created.
	 *
	 * @return cascade instance
	 */
	public OACascade getCascade() {
		if (cascade == null) {
			cascade = new OACascade();
		}
		return cascade;
	}

	/**
	 * Returns the map used to resolve GUID-based references during JSON reads.
	 * Lazily initializes the map if required.
	 *
	 * @return GUID-to-object map
	 */
	public Map<UUID, OAObject> getGuidMap() {
		if (hmGuidObject == null) {
			hmGuidObject = new HashMap<>();
		}
		return hmGuidObject;
	}

	/**
	 * Writes the full JSON serialization of a Hub and its contents to a file,
	 * applying OAObject-aware serializers and thread-local Jackson state.
	 *
	 * @param hub  Hub to serialize
	 * @param file destination file
	 * @throws JsonProcessingException if serialization fails
	 * @throws IOException if file I/O fails
	 */
	public void write(final Hub<? extends OAObject> hub, File file) throws JsonProcessingException, IOException {
		setStackItem(null);
		this.cascade = null;
		getObjectMapper().writerWithDefaultPrettyPrinter().withAttribute("oajson", this).writeValue(file, hub);
	}

	/**
	 * Serializes a Hub and all of its objects into a pretty-printed JSON string.
	 *
	 * @param hub Hub to serialize
	 * @return JSON representation
	 * @throws JsonProcessingException if serialization fails
	 */
	public String write(final Hub<? extends OAObject> hub) throws JsonProcessingException {
		setStackItem(null);
		this.cascade = null;
		final ObjectMapper objectMapper = getObjectMapper();
		String json = objectMapper.writerWithDefaultPrettyPrinter().withAttribute("oajson", this).writeValueAsString(hub);
		return json;
	}

	/**
	 * Reads a JSON file into an existing Hub, adding objects based on identity
	 * or GUID references. Supports OAObject-aware deserialization behavior.
	 *
	 * @param file       JSON input file
	 * @param hub        Hub to populate
	 * @param bIsLoading whether loading mode is active
	 * @throws Exception if deserialization fails
	 */
	public <T extends OAObject> void readIntoHub(final File file, final Hub<T> hub, final boolean bIsLoading) throws Exception {
		ObjectMapper om = getObjectMapper();
		final JsonNode nodeRoot = om.readTree(file);
		readIntoHub(om, nodeRoot, hub, bIsLoading);
	}

	/**
	 * Reads a JSON string into an existing Hub. Processes arrays of object
	 * definitions, numeric IDs, or GUID-based references, attaching objects
	 * accordingly.
	 *
	 * @param json       JSON text
	 * @param hub        Hub to populate
	 * @param bIsLoading whether loading mode is active
	 * @throws Exception if parsing fails
	 */
	public <T extends OAObject> void readIntoHub(final String json, final Hub<T> hub, final boolean bIsLoading) throws Exception {
		ObjectMapper om = getObjectMapper();
		final JsonNode nodeRoot = om.readTree(json);
		readIntoHub(om, nodeRoot, hub, bIsLoading);
	}

	/**
	 * Core routine for loading JSON array content into a Hub. Handles object
	 * nodes, numeric primary-key references, multipart-key strings, and GUID
	 * references. Uses the given ObjectMapper directly.
	 *
	 * @param om         ObjectMapper to use
	 * @param nodeRoot   root JSON node (expected to be an array)
	 * @param hub        Hub to populate
	 * @param bIsLoading whether loading mode is active
	 * @throws Exception if parsing or identity resolution fails
	 */
	public <T extends OAObject> void readIntoHub(final ObjectMapper om, final JsonNode nodeRoot, final Hub<T> hub,
			final boolean bIsLoading) throws Exception {

		reset();
		this.readObjectClass = hub.getObjectClass();

		hmGuidObject = null;
		// Map<UUID, OAObject> hmGuidMap = getGuidMap();

		final OAThreadLocalService srvcOAThreadLocal = ((OAThreadService) OARuntime.thread()).getThreadLocalService();
		boolean bWas = false;
		try {
			if (bIsLoading) {
				bWas = srvcOAThreadLocal.setLoading(true);
			} else {
				bWas = srvcOAThreadLocal.getSendSyncMessages();
				srvcOAThreadLocal.setSendSyncMessages(false);
			}

			if (nodeRoot.isArray()) {
				ArrayNode nodeArray = (ArrayNode) nodeRoot;
				int x = nodeArray.size();
				for (int i = 0; i < x; i++) {
					JsonNode node = nodeArray.get(i);
					if (node.isObject()) {
						T objx = om.readerFor(OAObject.class).withAttribute("oajson", this).readValue(node); // will use OAJacksondeserializer
						hub.add(objx);
					} else if (node.isNumber()) {
						// key
						OAObjectKey ok = OAJson.convertNumberToObjectKey(getReadObjectClass(), node.asInt());

                		final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(getReadObjectClass());
						OAObject objNew = (OAObject) og.objectsInternal().callObjectCacheGet(getReadObjectClass(), ok);
						if (objNew != null) {
							hub.add((T) objNew);
						} else {
							OADataSource dsx = OARuntime.datasource().get(getReadObjectClass());
							if (dsx != null) {
								objNew = (OAObject) dsx.getObject(getReadObjectClass(), ok);
							}
							hub.add((T) objNew);
						}
					} else {
						String s = node.textValue();
						if (s.indexOf("guid.") == 0) {
							s = s.substring(5);
							UUID guid = UUID.fromString(s);
							hub.add((T) getGuidMap().get(guid));
						} else {
							// convert multipart key to OAObjectKey
							OAObjectKey ok = OAJson.convertJsonSinglePartIdToObjectKey(getReadObjectClass(), s);

	                		final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(getReadObjectClass());
							OAObject objNew = (OAObject) og.objectsInternal().callObjectCacheGet(getReadObjectClass(), ok);
							if (objNew != null) {
								hub.add((T) objNew);
							} else {
								OADataSource dsx = OARuntime.datasource().get(getReadObjectClass());
								if (dsx != null) objNew = (OAObject) dsx.getObject(getReadObjectClass(), ok);
								hub.add((T) objNew);
							}
						}
					}
				}
			} else {
				// hub.add(readObject(json, hub.getObjectClass(), bIsLoading));
			}

		} finally {
			if (bIsLoading) {
				srvcOAThreadLocal.setLoading(bWas);
			} else {
				srvcOAThreadLocal.setSendSyncMessages(bWas);
			}
			readObjectClass = null;
		}
	}

	/**
	 * Converts a single-part ID string encoded in JSON into an {@link OAObjectKey}
	 * using the ID property definitions of the target OAObject class.
	 *
	 * @param clazz          OAObject class containing ID metadata
	 * @param strSinglePartId string representation of the key value(s)
	 * @return constructed OAObjectKey
	 */
	public static OAObjectKey convertJsonSinglePartIdToObjectKey(final Class<? extends OAObject> clazz, final String strSinglePartId) {
		final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(clazz);
		OAObjectInfo oi = og.objectsInternal().callObjectInfoGetOAObjectInfo(clazz);

		String[] ids = strSinglePartId.split("/-");
		Object[] ids2 = new Object[ids.length];
		int i = 0;
		for (OAPropertyInfo pi : oi.getPropertyInfos()) {
			if (pi.getId()) {
				ids2[i] = OAConv.convert(pi.getClassType(), ids[i]);
				i++;
			}
		}
		OAObjectKey ok = new OAObjectKey(ids2);
		return ok;
	}

	/**
	 * Converts a numeric ID into an {@link OAObjectKey} for an OAObject that has
	 * a single numeric primary key property.
	 *
	 * @param clazz OAObject class
	 * @param id    primary key value
	 * @return OAObjectKey containing the converted ID
	 */
	public static OAObjectKey convertNumberToObjectKey(final Class<? extends OAObject> clazz, final int id) {
		final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(clazz);
		OAObjectInfo oi = og.objectsInternal().callObjectInfoGetOAObjectInfo(clazz);

		Object[] ids2 = new Object[1];
		for (OAPropertyInfo pi : oi.getPropertyInfos()) {
			if (pi.getId()) {
				ids2[0] = OAConv.convert(pi.getClassType(), id);
				break;
			}
		}
		OAObjectKey ok = new OAObjectKey(ids2);
		return ok;
	}

	/**
	 * Converts an {@link OAObjectKey} into a JSON-friendly string representation,
	 * formatting multipart keys with separators or encoding GUID-based keys when
	 * no ID values are present.
	 *
	 * @param oaObjKey source object key
	 * @return JSON-friendly single-part key string, or {@code null} if key is null
	 */
	public static String convertObjectKeyToJsonSinglePartId(OAObjectKey oaObjKey) {
		if (oaObjKey == null) {
			return null;
		}

		String ids = null;
		Object[] objs = oaObjKey.getObjectIds();
		if (objs != null) {
			boolean bHasId = false;
			for (Object obj : objs) {

				if (obj instanceof OADate) {
					obj = ((OADate) obj).toString(OADate.JsonFormat);
				}

				bHasId |= (obj != null);
				if (ids == null) {
					ids = "" + obj;
				} else {
					ids += "-" + OAConv.toString(obj);
				}
			}
			if (!bHasId) {
				ids = "guid." + oaObjKey.getGuid();
			}
		}
		return ids;
	}

	/**
	 * Serializes method argument values into a JSON array, including type hints
	 * when polymorphic objects are supplied. Supports selective parameter omission.
	 *
	 * @param method                   method whose parameters are being serialized
	 * @param argValues                argument values
	 * @param lstIncludePropertyPathss optional property paths per argument
	 * @param skipParams               parameter indexes to skip
	 * @return JSON array string
	 * @throws Exception if serialization fails
	 */
	public static String convertMethodArgumentsToJson(final Method method, final Object[] argValues,
			final List<String>[] lstIncludePropertyPathss, final int[] skipParams) throws Exception {

		final OAJson oaj = new OAJson();
		return _convertMethodArgumentsToJson(oaj, method, argValues, lstIncludePropertyPathss, skipParams);
	}

	/**
	 * Internal implementation of method-argument serialization. Writes argument
	 * values and type-hint markers into a Jackson ArrayNode.
	 *
	 * @param oaj                      OAJson instance to use
	 * @param method                   method whose arguments are being serialized
	 * @param argValues                argument values
	 * @param lstIncludePropertyPathss optional per-argument property paths
	 * @param skipParams               indexes of parameters to skip
	 * @return JSON array text
	 * @throws Exception if serialization fails
	 */
	protected static String _convertMethodArgumentsToJson(final OAJson oaj, final Method method, final Object[] argValues,
			final List<String>[] lstIncludePropertyPathss, final int[] skipParams) throws Exception {

		final ObjectMapper om = oaj.getObjectMapper();

		final ArrayNode arrayNode = om.createArrayNode();

		if (argValues == null) {
			return null;
		}

		final Parameter[] mps = method.getParameters();

		int i = -1;
		for (Object obj : argValues) {
			i++;

			if (skipParams != null && skipParams.length > 0) {
				boolean b = false;
				for (int p : skipParams) {
					if (p == i) {
						b = true;
						break;
					}
				}
				if (b) {
					continue;
				}
			}

			final Parameter param = mps[i];
			final Class paramClass = param.getType();
			if (obj != null && !obj.getClass().equals(paramClass) && !paramClass.isPrimitive()) {
				// need to know the correct cast
				String s = methodNextArgumentParamClass + obj.getClass().getName();
				arrayNode.add(s);
			}

			JsonNode node = om.valueToTree(obj);

			arrayNode.add(node);
		}

		return arrayNode.toPrettyString();
	}

	private static final String methodNextArgumentParamClass = "OANextParamClass:";

	/**
	 * Deserializes a JSON array of values into argument objects for the given
	 * method. Type-hint markers are processed to restore polymorphic types.
	 *
	 * @param jsonArray JSON array input
	 * @param method    method whose parameters are being reconstructed
	 * @return array of argument values
	 * @throws Exception if parsing fails
	 */
	public static Object[] convertJsonToMethodArguments(String jsonArray, Method method) throws Exception {

		final OAJson oaj = new OAJson();
		final ObjectMapper om = oaj.getObjectMapper();

		JsonNode nodeRoot = om.readTree(jsonArray);

		ArrayNode nodeArray;

		if (nodeRoot instanceof ArrayNode) {
			nodeArray = (ArrayNode) nodeRoot;
		} else {
			nodeArray = om.createArrayNode();
			if (nodeRoot != null) {
				nodeArray.add(nodeRoot);
			}
		}

		Object[] objs = convertJsonToMethodArguments(oaj, nodeArray, method, null);
		return objs;
	}

	/**
	 * Converts a JSON ArrayNode into argument values for the given method,
	 * optionally skipping specified parameter positions.
	 *
	 * @param nodeArray JSON array node
	 * @param method    method whose parameters are being reconstructed
	 * @param skipParams indexes of parameters to skip
	 * @return array of argument values
	 * @throws Exception if deserialization fails
	 */
	public static Object[] convertJsonToMethodArguments(ArrayNode nodeArray, Method method, final int[] skipParams) throws Exception {
		final OAJson oaj = new OAJson();
		final ObjectMapper om = oaj.getObjectMapper();

		Object[] objs = convertJsonToMethodArguments(oaj, nodeArray, method, null);
		return objs;
	}

	/**
	 * Core implementation for converting JSON nodes into method argument values.
	 * Handles OAObject vs. non-OAObject types, type-hint markers, and parameter
	 * skipping.
	 *
	 * @param oaj        OAJson instance to use
	 * @param nodeArray  JSON array node containing argument data
	 * @param method     method whose arguments are being reconstructed
	 * @param skipParams optional indexes to skip
	 * @return array of reconstructed argument values
	 * @throws Exception if type conversion fails
	 */
	protected static Object[] convertJsonToMethodArguments(OAJson oaj, ArrayNode nodeArray, Method method, final int[] skipParams)
			throws Exception {
		if (nodeArray == null || method == null) {
			return null;
		}

		Parameter[] mps = method.getParameters();
		if (mps == null) {
			return null;
		}
		final Object[] margs = new Object[mps.length];

		final int nodeArraySize = nodeArray.size();

		int nodeArrayPos = 0;
		for (int i = 0; i < mps.length && nodeArrayPos < nodeArraySize; i++) {
			if (skipParams != null && skipParams.length > 0) {
				boolean b = false;
				for (int p : skipParams) {
					if (p == i) {
						b = true;
						break;
					}
				}
				if (b) {
					continue;
				}
			}

			final Parameter param = mps[i];
			Class paramClass = param.getType();

			JsonNode node = nodeArray.get(nodeArrayPos);

			if (node instanceof TextNode) {
				String s = ((TextNode) node).asText();
				if (s.startsWith(methodNextArgumentParamClass)) {
					s = s.substring(methodNextArgumentParamClass.length());
					paramClass = Class.forName(s);
					nodeArrayPos++;
					node = nodeArray.get(nodeArrayPos);
				}
			}

			Object objx;
			if (OAObject.class.isAssignableFrom(paramClass)) {
				objx = oaj.readObject(node.toString(), paramClass, false);
			} else {
				ObjectMapper om = oaj.getObjectMapper();
				objx = om.readValue(node.toString(), paramClass);

				//qqqqqqqqqqqqqqqqqvv

			}
			margs[i] = objx;
			nodeArrayPos++;
		}
		return margs;
	}

	/**
	 * Parses the supplied JSON string into a {@link JsonNode} tree using this
	 * OAJson instance’s configured {@link ObjectMapper}. Internal state is reset
	 * prior to parsing.
	 *
	 * @param json the JSON text to parse
	 * @return root JsonNode of the parsed structure
	 * @throws Exception if parsing fails
	 */
	public JsonNode readTree(String json) throws Exception {
		reset();
		JsonNode node = getObjectMapper().readTree(json);
		return node;
	}

	/**
	 * Parses the supplied JSON input stream into a {@link JsonNode} tree using
	 * this OAJson instance’s configured {@link ObjectMapper}. Internal state is
	 * reset prior to parsing.
	 *
	 * @param is input stream containing JSON data
	 * @return root JsonNode of the parsed structure
	 * @throws Exception if parsing fails or stream errors occur
	 */
	public JsonNode readTree(InputStream is) throws Exception {
		reset();
		JsonNode node = getObjectMapper().readTree(is);
		return node;
	}

	// todo:  under constructions[]

	/**
	 * Navigates the given JsonNode hierarchy using a dotted property-path
	 * expression. Returns the final node found along the traversal path.
	 *
	 * @param parentNode the starting node
	 * @param propertyPath dotted property-path (e.g., "address.city")
	 * @return the JsonNode at the end of the path, or {@code null} if missing
	 */
	public JsonNode getNode(JsonNode parentNode, String propertyPath) {
		String[] ss = propertyPath.split("\\.");
		for (String prop : ss) {
			String s = OAString.field(prop, "[", 2);
			prop = OAString.field(prop, "[", 1);

			JsonNode jn = parentNode.get(prop);
			parentNode = jn;
		}
		return parentNode;
	}

	/**
	 * Builds a dotted property-path representing the current traversal position
	 * during read/write operations, based on the stack of {@link StackItem}
	 * entries.
	 *
	 * @return the active property path, or {@code null} if none
	 */
	public String getCurrentPropertyPath() {
		StackItem si = stackItem;
		if (si == null) {
			return null;
		}

		String pp = null;
		for (; si != null;) {
			if (si.li != null) {
				if (pp == null) {
					pp = si.li.getLowerName();
				} else {
					pp = si.li.getLowerName() + "." + pp;
				}
			}
			si = si.parent;
		}

		return pp;
	}

	/**
	 * Callback invoked during JSON serialization/deserialization to allow
	 * renaming of properties. The default implementation returns the name
	 * unchanged.
	 *
	 * @param obj         the owning object
	 * @param defaultName original property name
	 * @return property name to use
	 */
	public String getPropertyNameCallback(Object obj, String defaultName) {
		return defaultName;
	}

	/**
	 * Callback invoked during JSON read/write to supply an alternate property
	 * value. The default implementation returns the provided default value.
	 *
	 * @param obj          the owning object
	 * @param propertyName property being accessed
	 * @param defaultValue default value obtained from OAObject
	 * @return value to use for JSON output or input
	 */
	public Object getPropertyValueCallback(Object obj, String propertyName, Object defaultValue) {
		return defaultValue;
	}

	/**
	 * Callback used to determine whether a property should be included during
	 * JSON read/write operations. The default implementation always returns
	 * {@code true}.
	 *
	 * @param obj          the owning object
	 * @param propertyName property to check
	 * @return {@code true} to use the property, otherwise {@code false}
	 */
	public boolean getUsePropertyCallback(Object obj, String propertyName) {
		return true;
	}

	/**
	 * Hook method invoked before a JSON node is deserialized into an object.
	 * Subclasses may override to inspect or transform the node.
	 *
	 * @param node JSON node that will be deserialized
	 */
	public void beforeReadCallback(JsonNode node) {
	}

	/**
	 * Hook method invoked after a JSON node has been deserialized into an object.
	 * Subclasses may override to perform post-processing or validation.
	 *
	 * @param node   source JSON node
	 * @param objNew newly created or updated object
	 */
	public void afterReadCallback(JsonNode node, Object objNew) {
	}

	/**
	 * Enables or disables POJO writing mode, which includes additional properties
	 * such as importMatch information that may not exist in the OAObject model.
	 *
	 * @param b {@code true} to enable POJO-mode output
	 */
	public void setWriteAsPojo(boolean b) {
		this.bWriteAsPojo = b;
	}

	/**
	 * Returns whether POJO-mode JSON writing is active. When enabled, additional
	 * properties not required by OAObjects may be emitted.
	 *
	 * @return {@code true} if POJO writing mode is enabled, otherwise {@code false}
	 */
	public boolean getWriteAsPojo() {
		return this.bWriteAsPojo;
	}

}
