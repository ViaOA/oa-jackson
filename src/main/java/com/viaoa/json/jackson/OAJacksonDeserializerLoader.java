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
package com.viaoa.json.jackson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.viaoa.converter.OAConv;
import com.viaoa.datasource.OADataSource;
import com.viaoa.datasource.OADataSourceIterator;
import com.viaoa.datasource.objectcache.OADataSourceObjectCache;
import com.viaoa.datetime.OADate;
import com.viaoa.datetime.OADateTime;
import com.viaoa.datetime.OATime;
import com.viaoa.filter.OAFilter;
import com.viaoa.filter.OAQueryFilter;
import com.viaoa.find.OAFinder;
import com.viaoa.graph.OAGraph;
import com.viaoa.graph.service.object.OAObjectCacheService;
import com.viaoa.graph.service.object.OAObjectInfoService;
import com.viaoa.graph.service.object.OAObjectPropertyService;
import com.viaoa.graph.service.object.OAObjectReflectService;
import com.viaoa.hub.Hub;
import com.viaoa.json.OAJson;
import com.viaoa.json.OAJson.StackItem;
import com.viaoa.lang.OAArray;
import com.viaoa.lang.OAString;
import com.viaoa.lang.oa.VString;
import com.viaoa.metadata.OAFkeyInfo;
import com.viaoa.metadata.OALinkInfo;
import com.viaoa.metadata.OAPropertyInfo;
import com.viaoa.object.OAObject;
import com.viaoa.object.OAObjectKey;
import com.viaoa.path.OAPath;
import com.viaoa.metadata.pojo.*;
import com.viaoa.runtime.OARuntime;
import com.viaoa.runtime.OAThreadLocalService;
import com.viaoa.runtime.OAThreadService;
import com.viaoa.select.OASelect;

// todo: unit test with CorpToStore model ... to check multipart keys

/**
 * Core loader used by {@link OAJson} and {@link OAJacksonDeserializer} to
 * convert a Jackson {@link JsonNode} tree into an {@link OAObject} graph.
 * <p>
 * The loader is responsible for:
 * <ul>
 *   <li>Finding existing {@link OAObject} instances using GUIDs, primary keys,
 *       import-match properties, and unique link properties.</li>
 *   <li>Creating new {@link OAObject} instances when no match is found and
 *       applying ID/auto-assign semantics.</li>
 *   <li>Populating scalar properties from JSON, including callback hooks and
 *       name-mapping via {@link OAJson} configuration.</li>
 *   <li>Recursively loading one-to-one and one-to-many links and wiring them
 *       into the object graph.</li>
 *   <li>Handling POJO-based JSON where keys do not directly map to OAObject
 *       primary key properties.</li>
 * </ul>
 * This class is intentionally low-level and is normally used only via
 * {@link OAJson} and {@link OAJacksonDeserializer}.
 */
public class OAJacksonDeserializerLoader {

	/**
	 * Owning {@link OAJson} context that drives deserialization behavior, including
	 * property callbacks, naming conventions, and stack management for nested
	 * objects.
	 */
	private final OAJson oajson;
	
	/**
	 * Flag indicating whether the loader is working in POJO mode.
	 * <p>
	 * When {@code true}, POJO metadata and mappings are used to interpret JSON
	 * keys; otherwise, OAObject metadata is used directly.
	 */
	private final boolean bUsesPojo;

	/**
	 * Enables or disables debug output during loading.
	 * <p>
	 * When {@code true}, internal operations emit trace messages to
	 * {@link System#out} via the {@link #debug(StackItem, boolean, String)} helper.
	 */
	private boolean debug = false;
	
	/**
	 * Counter tracking how many times {@link #load(OAJson.StackItem)} has been
	 * invoked.
	 * <p>
	 * Primarily used to prefix debug messages with a simple sequence number.
	 */
	private int cntLoadCalled;

	/**
	 * Collection of POJO link references that could not be resolved on the first
	 * pass.
	 * <p>
	 * These entries are revisited once the initial load completes, allowing
	 * cross-object references to be resolved after all objects are created.
	 */
	private final List<RetryPojoReference> alRetryPojoReference = new ArrayList();

	/**
	 * Creates a new loader bound to the given {@link OAJson} context.
	 * <p>
	 * The context determines whether POJO mode is used and supplies callbacks,
	 * naming rules, and the active stack item during loading.
	 *
	 * @param oaj the {@link OAJson} instance controlling deserialization behavior
	 */
	public OAJacksonDeserializerLoader(OAJson oaj) {
		this.oajson = oaj;
		this.bUsesPojo = oaj.getReadingPojo();
	}

	/**
	 * Convenience overload that loads JSON into the given root object using its
	 * runtime type.
	 * <p>
	 * This method delegates to
	 * {@link #load(JsonNode, OAObject, Class)} with a {@code null} type hint and
	 * returns the resulting root instance.
	 *
	 * @param <T>  the concrete {@link OAObject} type
	 * @param node the JSON node representing the root object and its subtree
	 * @param root the existing root object to populate, or {@code null} to create
	 * @return the loaded root object, which may be newly created or updated
	 */
	public <T extends OAObject> T load(final JsonNode node, final T root) {
		T t = load(node, root, null);
		return t;
	}

	/**
	 * Entry point for loading a JSON subtree into an {@link OAObject} graph.
	 * <p>
	 * A {@link OAJson.StackItem} is created to represent the root, associated
	 * metadata is resolved, and {@link #load(OAJson.StackItem)} is invoked. Any
	 * deferred POJO references are then retried before restoring the previous
	 * stack item.
	 *
	 * @param <T>   the concrete {@link OAObject} type
	 * @param node  the JSON node to load; may be {@code null}
	 * @param root  the existing root object, or {@code null} to create one
	 * @param clazz optional target type hint; if {@code null}, the type is taken
	 *              from {@code root}
	 * @return the loaded root object, or {@code null} if nothing could be created
	 */
	public <T extends OAObject> T load(final JsonNode node, final T root, Class<T> clazz) {
		if (node == null) {
			return root;
		}
		if (clazz == null) {
			if (root == null) {
				return null;
			}
			clazz = (Class<T>) root.getClass();
		}
		OAJson.StackItem stackItem = new OAJson.StackItem();
		final OAGraph og = OARuntime.graph(clazz);
		stackItem.oi = og.internal().objects().info().getOAObjectInfo(clazz);
		stackItem.obj = root;
		stackItem.node = node;

		final StackItem siHold = oajson.getStackItem();
		oajson.setStackItem(stackItem);

		try {
			load(stackItem);
			retryPojoReferences();
		} finally {
			oajson.setStackItem(siHold);
		}

		return (T) stackItem.obj;
	}

	/**
	 * Core routine that loads a single {@link OAObject} and its links from the
	 * current {@link StackItem}.
	 * <p>
	 * The method attempts to find an existing instance, creates one if needed,
	 * loads identifier properties when appropriate, and then populates scalar
	 * properties and links. Debug messages are emitted when debugging is enabled.
	 *
	 * @param stackItem the current stack frame describing the object and JSON node
	 * @return {@code true} if the stack item was processed, {@code false} if it
	 *         was ignored or invalid
	 */
	protected boolean load(final StackItem stackItem) {
		if (stackItem == null) {
			return false;
		}

		String debug = ++cntLoadCalled + ")";
		debug(stackItem, "BEG " + debug);

		if (stackItem.node == null) {
			return false;
		}

		// 1:
		findExistingObject(stackItem);

		if (stackItem.node.isObject()) {
			if (stackItem.obj == null) {
				// 2a:
				createObject(stackItem);
			} else {
				if (stackItem.li != null && stackItem.li.getAutoCreateNew()) {
					if (stackItem.obj.isNew()) {
						// 2b:
						loadObjectIdProperties(stackItem);
					}
				}
			}

			// 3:
			loadObject(stackItem);
		}
		debug(stackItem, "END " + debug);
		return true;
	}

	/**
	 * Creates a new {@link OAObject} instance for the given {@link StackItem}.
	 * <p>
	 * The object type is taken from the associated {@link OAPropertyInfo} metadata.
	 * Identifier properties are loaded, and initialization callbacks are invoked
	 * via {@link OAObjectDelegate#initializeAfterLoading(OAObject, boolean, boolean, boolean)}
	 * when appropriate.
	 *
	 * @param stackItem the stack frame describing the object to create
	 */
	protected void createObject(final StackItem stackItem) {
		final Class clazz = stackItem.oi.getForClass();
		debug2(stackItem, "createObject");

		oajson.beforeReadCallback(stackItem.node);
		final OAGraph og = OARuntime.graph(clazz);
		stackItem.obj = (OAObject) og.internal().objects().reflect().createNewObject(clazz);

		boolean bNeedsAssignedId = loadObjectIdProperties(stackItem);

		final OAThreadLocalService srvcOAThreadLocal = ((OAThreadService) OARuntime.thread()).getThreadLocalService();  
		if (srvcOAThreadLocal.isLoading()) {
			 srvcOAThreadLocal.setLoading(false);
			try {
				og.internal().objects().initialize().initializeAfterLoading((OAObject) stackItem.obj, bNeedsAssignedId, false, false);
			} finally {
				srvcOAThreadLocal.setLoading(true);
			}
		}
	}

	/**
	 * Loads identifier-related properties from JSON into the current object.
	 * <p>
	 * For POJO mode, key properties are resolved using {@link PojoProperty}
	 * metadata; otherwise, OAObject ID properties are used directly. The method
	 * applies callbacks and format-aware conversion before assigning values.
	 *
	 * @param stackItem the stack frame describing the object and its JSON node
	 * @return {@code true} if an assigned ID is still needed, {@code false} if all
	 *         required ID values were provided
	 */
	protected boolean loadObjectIdProperties(final StackItem stackItem) {
		boolean bNeedsAssignedId = false;
		if (bUsesPojo) {
			for (OAPropertyInfo pi : stackItem.oi.getPropertyInfos()) {
				if (pi.getId() && pi.getAutoAssign()) {
					bNeedsAssignedId = true;
					break;
				}
			}

			for (PojoProperty pp : PojoDelegate.getPojoPropertyKeys(stackItem.oi.getPojo())) {
				String propertyName = pp.getName();
				if (!oajson.getUsePropertyCallback(null, propertyName)) {
					continue;
				}
				propertyName = oajson.getPropertyNameCallback(null, propertyName);

				JsonNode jn = stackItem.node.get(propertyName);

				final OAGraph og = OARuntime.graph(stackItem.oi.getForClass());
				OAPropertyInfo pi = og.internal().objects().info().getPropertyInfo(stackItem.oi, propertyName);
				if (pi == null) {
					continue;
				}
				if (pi.getAutoAssign()) {
					bNeedsAssignedId = false;
				}

				Object objx = convert(jn, pi);

				objx = oajson.getPropertyValueCallback(null, pi.getLowerName(), objx);

				stackItem.obj.setProperty(pi.getLowerName(), objx);
			}
		} else {
			for (OAPropertyInfo pi : stackItem.oi.getPropertyInfos()) {
				if (!pi.getId()) {
					continue;
				}

				String propertyName = pi.getLowerName();
				if (!oajson.getUsePropertyCallback(null, propertyName)) {
					continue;
				}
				
				propertyName = oajson.getPropertyNameCallback(null, stackItem.oi.getJsonUsesCapital() ? pi.getName() : propertyName);

				JsonNode jn = stackItem.node.get(propertyName);

				if (jn == null) {
					bNeedsAssignedId |= pi.getAutoAssign();
					continue;
				}

				Object objx = convert(jn, pi);

				objx = oajson.getPropertyValueCallback(null, pi.getLowerName(), objx);

				stackItem.obj.setProperty(pi.getLowerName(), objx);
			}
		}
		return bNeedsAssignedId;
	}

	/**
	 * Populates the current object's scalar properties and links from its JSON
	 * node.
	 * <p>
	 * The method first loads non-ID properties, then processes one-to-one and
	 * one-to-many links found in the JSON, and finally resolves remaining
	 * references that require key-based lookup.
	 *
	 * @param stackItem the stack frame describing the object being populated
	 * @throws RuntimeException if the JSON node is not an object
	 */
	protected void loadObject(final StackItem stackItem) {
		if (stackItem.node == null || !stackItem.node.isObject()) {
			throw new RuntimeException("loadObject does not have a node.isObject=true");
		}

		debug2(stackItem, "loadObject " + stackItem.oi.getName());

		loadObjectProperties(stackItem);

		final Set<OALinkInfo> hsLinkInfoOneLoaded = new HashSet();

		if (stackItem.li != null && stackItem.li.isMany2One()) {
			hsLinkInfoOneLoaded.add(stackItem.li.getReverseLinkInfo());
		}

		Iterator<String> itx = stackItem.node.fieldNames();
		for (; itx.hasNext();) {
			String name = itx.next();

			OALinkInfo lix = stackItem.oi.getLinkInfo(name);
			if (lix == null) {
				continue;
			}
			if (lix.isOne()) {
				if (!hsLinkInfoOneLoaded.contains(lix)) {
					if (loadObjectOneLink(stackItem, lix)) {
						hsLinkInfoOneLoaded.add(lix);
					}
				}
			} else {
				loadObjectManyLink(stackItem, lix);
			}
		}

		loadObjectReferences(stackItem, hsLinkInfoOneLoaded);
	}

	/**
	 * Loads non-identifier scalar properties for the current object.
	 * <p>
	 * Optional GUID handling is performed for non-POJO mode, followed by
	 * iteration over all {@link OAPropertyInfo} entries. Each property is mapped
	 * from the JSON node using naming conventions, converted, passed through
	 * callbacks, and then assigned.
	 *
	 * @param stackItem the stack frame describing the object and its JSON node
	 */
	protected void loadObjectProperties(final StackItem stackItem) {
		OAObjectKey objKey = stackItem.obj.getObjectKey();

		// debug2(stackItem, "loadObjectProperties");

		if (!bUsesPojo) {
			JsonNode jn = stackItem.node.get("guid");
			if (jn != null) {
				UUID guid = UUID.fromString(jn.asText());				
				if (oajson != null) {
					oajson.getGuidMap().put(guid, stackItem.obj);
				}
			}
		}

		// load properties
		for (OAPropertyInfo pi : stackItem.oi.getPropertyInfos()) {
			if (pi.getId()) {
				continue;
			}

			if (!oajson.getUsePropertyCallback(stackItem.obj, pi.getLowerName())) {
				continue;
			}

			String propertyName = stackItem.oi.getJsonUsesCapital() ? pi.getName() : pi.getLowerName();
			
			propertyName = oajson.getPropertyNameCallback(stackItem.obj, propertyName);

			JsonNode jn = stackItem.node.get(propertyName);
			if (jn == null) {
				continue;
			}

			Object objx = convert(jn, pi);

			objx = oajson.getPropertyValueCallback(stackItem.obj, pi.getLowerName(), objx);

			stackItem.obj.setProperty(pi.getLowerName(), objx);
		}
	}

	/**
	 * Loads a single-valued link property from JSON for the given link metadata.
	 * <p>
	 * A child {@link StackItem} is constructed for the linked type, populated with
	 * the appropriate JSON node, and delegated to
	 * {@link #loadObjectOneLink(OAJson.StackItem, OAJson.StackItem)}.
	 *
	 * @param stackItem the parent stack frame containing the owning object
	 * @param li        the link metadata describing the one link
	 * @return {@code true} if a linked object was loaded or resolved, otherwise
	 *         {@code false}
	 */
	protected boolean loadObjectOneLink(final StackItem stackItem, final OALinkInfo li) {
		if (li.getType() != li.TYPE_ONE) {
			return false;
		}
		if (li.getPrivateMethod()) {
			return false;
		}

		if (!oajson.getUsePropertyCallback(stackItem.obj, li.getLowerName())) {
			return false;
		}

		// debug2(stackItem, "loadObjectOneLink " + li.getName());

		StackItem stackItemChild = new StackItem();
		stackItemChild.parent = stackItem;
		stackItemChild.oi = li.getToObjectInfo();
		stackItemChild.li = li;
		stackItemChild.node = stackItem.node.get(stackItem.oi.getJsonUsesCapital() ? li.getName() : li.getLowerName());

		try {
			oajson.setStackItem(stackItemChild);
			boolean b = loadObjectOneLink(stackItem, stackItemChild);
			return b;
		} finally {
			oajson.setStackItem(stackItem);
		}
	}

	/**
	 * Loads and assigns a single-valued link using a prepared child
	 * {@link StackItem}.
	 * <p>
	 * The child stack item is processed via {@link #load(OAJson.StackItem)} and,
	 * if successful, the resulting object is assigned to the parent's link
	 * property.
	 *
	 * @param stackItem      the parent stack frame
	 * @param stackItemChild the child stack frame representing the linked object
	 * @return {@code true} if the link was successfully loaded, otherwise
	 *         {@code false}
	 */
	protected boolean loadObjectOneLink(final StackItem stackItem, final StackItem stackItemChild) {
		debug2(stackItem, "loadObjectOneLink " + stackItemChild.li.getName());

		if (stackItem.node == null) {
			return false;
		}

		boolean b = load(stackItemChild);
		if (b) {
			stackItem.obj.setProperty(stackItemChild.li.getLowerName(), stackItemChild.obj);
		}
		return b;
	}

	/**
	 * Loads a collection-valued link from a JSON array.
	 * <p>
	 * For each array element, a child {@link StackItem} is created and loaded,
	 * and the resulting objects are added to the target {@link Hub}. Existing
	 * items not present in the JSON are removed, and hub order is updated to match
	 * the JSON sequence. After loading, an {@code afterRead} callback is invoked.
	 *
	 * @param stackItem the parent stack frame containing the owner object
	 * @param li        the link metadata describing the many-valued association
	 */
	protected void loadObjectManyLink(final StackItem stackItem, final OALinkInfo li) {
		// load links of type=many
		debug2(stackItem, "loadObjectManyLink " + li.getName());
		if (li.getType() != li.TYPE_MANY) {
			return;
		}
		if (li.getPrivateMethod()) {
			return;
		}

		if (!oajson.getUsePropertyCallback(stackItem.obj, li.getLowerName())) {
			return;
		}

		String propertyName = oajson.getPropertyNameCallback(stackItem.obj, stackItem.oi.getJsonUsesCapital() ? li.getName() : li.getLowerName());
		JsonNode nodex = stackItem.node.get(propertyName);

		if (!(nodex instanceof ArrayNode)) {
			return;
		}
		Hub<OAObject> hub = (Hub<OAObject>) li.getValue(stackItem.obj);
		ArrayNode nodeArray = (ArrayNode) nodex;

		List<OAObject> alAdded = new ArrayList();
		int x = nodeArray.size();
		for (int i = 0; i < x; i++) {
			nodex = nodeArray.get(i);

			StackItem stackItemChild = new StackItem();
			stackItemChild.parent = stackItem;
			stackItemChild.oi = li.getToObjectInfo();
			stackItemChild.li = li;
			stackItemChild.node = nodex;

			try {
				oajson.setStackItem(stackItemChild);
				load(stackItemChild);
				alAdded.add(stackItemChild.obj);
				if (!hub.contains(stackItemChild.obj)) {
					hub.add(stackItemChild.obj);
				}

			} finally {
				oajson.setStackItem(stackItem);
			}
		}

		List<OAObject> alRemove = new ArrayList();
		for (OAObject objx : hub) {
			if (!alAdded.contains(objx)) {
				alRemove.add(objx);
			}
		}
		for (OAObject objx : alRemove) {
			hub.remove(objx);
		}

		// same order
		int i = 0;
		for (OAObject objx : alAdded) {
			int pos = hub.getPos(objx);
			if (pos != i) {
				hub.move(pos, i);
			}
			i++;
		}

		oajson.afterReadCallback(stackItem.node, stackItem.obj);
	}

	/**
	 * Resolves link-one references that were not explicitly provided as nested
	 * JSON objects.
	 * <p>
	 * Depending on POJO mode, either
	 * {@link #loadObjectNonPojoReferences(OAJson.StackItem, Set)} or
	 * {@link #loadObjectPojoReferences(OAJson.StackItem, Set)} is invoked to
	 * perform key-based lookups for remaining links.
	 *
	 * @param stackItem              the stack frame describing the current object
	 * @param hsLinkInfoOneLoaded    set of link-one definitions already populated
	 *                               from nested JSON
	 */
	protected void loadObjectReferences(final StackItem stackItem, final Set<OALinkInfo> hsLinkInfoOneLoaded) {
		if (!bUsesPojo) {
			loadObjectNonPojoReferences(stackItem, hsLinkInfoOneLoaded);
		} else {
			loadObjectPojoReferences(stackItem, hsLinkInfoOneLoaded);
		}
	}

	/**
	 * Resolves link-one references for non-POJO mode using foreign key metadata.
	 * <p>
	 * For each unresolved link, foreign key values are gathered from the current
	 * or parent JSON nodes, converted, and used to locate existing objects via
	 * the cache or data source. If no object is found, the link property is
	 * populated with an {@link OAObjectKey} placeholder.
	 *
	 * @param stackItem           the stack frame for the current object
	 * @param hsLinkInfoOneLoaded set of link-one definitions already resolved
	 */
	protected void loadObjectNonPojoReferences(final StackItem stackItem, final Set<OALinkInfo> hsLinkInfoOneLoaded) {
		// load linkOne using fkeys, if the linkOne node did not exist
		for (OALinkInfo li : stackItem.oi.getLinkInfos()) {
			if (!li.isOne()) {
				continue;
			}
			if (hsLinkInfoOneLoaded.contains(li)) {
				continue;
			}

			boolean bHasNull = false;
			ArrayList<Object> alKey = new ArrayList();
			for (OAFkeyInfo fi : li.getFkeyInfos()) {
				if (fi.getFromPropertyInfo() == null) {
					continue;
				}

				JsonNode jn = null;
				if (stackItem.parent != null) {
					if (stackItem.parent.li.getReverseLinkInfo() == li) {
						String s = stackItem.oi.getJsonUsesCapital() ? fi.getFromPropertyInfo().getName() : fi.getFromPropertyInfo().getLowerName();
						jn = stackItem.parent.node.get(s);
					}
				}
				if (jn == null) {
					String s = li.getToObjectInfo().getJsonUsesCapital() ? fi.getToPropertyInfo().getName() : fi.getToPropertyInfo().getLowerName();
					jn = stackItem.node.get(s);
				}

				if (jn == null) {
					bHasNull = true;
				}
				else {
					Object objx = convert(jn, fi.getToPropertyInfo());
					alKey.add(objx);
					bHasNull |= (objx == null);
				}
			}

			Object[] objs = alKey.toArray(new Object[alKey.size()]);
			OAObjectKey ok = (bHasNull || objs == null || objs.length == 0) ? null : new OAObjectKey(objs);

			OAObject obj;
			if (bHasNull) {
				obj = null;
			} else {
				final OAGraph og = OARuntime.graph(li.getToClass());
				obj = (OAObject) og.internal().objects().cache().get(li.getToClass(), ok);
				if (obj == null) {
					OADataSource ds = OARuntime.datasource().get(li.getToClass());
					if (ds != null) obj = (OAObject) ds.getObject(li.getToClass(), ok);
				}
			}
			
			if (obj == null && ok != null) {
				final OAGraph og = OARuntime.graph(stackItem.obj);
				og.internal().objects().property().setProperty(stackItem.obj, li.getName(), ok);
			} else {
				stackItem.obj.setProperty(li.getName(), obj);
			}
		}
	}

	/**
	 * Resolves link-one references for POJO mode using POJO key definitions.
	 * <p>
	 * Foreign-key, import-match, and link-unique strategies are tried in sequence
	 * for each {@link PojoLinkOne}. When an object cannot be found immediately,
	 * a {@link RetryPojoReference} is queued for later retry.
	 *
	 * @param stackItem           the stack frame describing the current object
	 * @param hsLinkInfoOneLoaded set of link-one definitions already resolved
	 */
	protected void loadObjectPojoReferences(final StackItem stackItem, final Set<OALinkInfo> hsLinkInfoOneLoaded) {
		// load Pojo key properties (fkey, importMatch, linkUnique+equalsPp)

		for (PojoLink pl : stackItem.oi.getPojo().getPojoLinks()) {
			PojoLinkOne plo = pl.getPojoLinkOne();
			if (plo == null) {
				continue;
			}

			final OALinkInfo li = stackItem.oi.getLinkInfo(pl.getName());
			if (hsLinkInfoOneLoaded.contains(li)) {
				continue;
			}

			if (!loadObjectPojoFkeyReferences(stackItem, plo, li)) {
				if (!loadObjectPojoImportMatchReferences(stackItem, plo, li)) {
					loadObjectPojoUniqueReferences(stackItem, plo, li);
				}
			}
		}
	}

	/**
	 * Attempts to resolve a POJO-based link-one reference using foreign-key
	 * properties defined in {@link PojoLinkOne}.
	 * <p>
	 * JSON values for the POJO key properties are converted and collected. If all
	 * required values are present, an {@link OAObjectKey} is constructed and used
	 * to locate the referenced object in the cache or data source. If the object
	 * cannot be found, a placeholder key is assigned.
	 *
	 * @param stackItem the current stack frame
	 * @param plo       POJO metadata describing the link-one foreign-key mapping
	 * @param li        the corresponding OA link metadata
	 * @return {@code true} once processing is complete; always returns true
	 */
	protected boolean loadObjectPojoFkeyReferences(final StackItem stackItem, final PojoLinkOne plo, final OALinkInfo li) {
		List<PojoProperty> alPojoProperty = PojoLinkOneDelegate.getLinkFkeyPojoProperties(plo);
		if (alPojoProperty == null || alPojoProperty.size() == 0) {
			return false;
		}

		final Map<String, Object> hm = new HashMap<>();
		for (final PojoProperty pjp : alPojoProperty) {
			final String fkeyName = pjp.getName();
			JsonNode jn = stackItem.node.get(fkeyName);
			if (jn == null) {
				hm.clear();
				break;
			}

			OAPath pp = new OAPath(stackItem.oi.getForClass(), pjp.getPropertyPath());
			OAPropertyInfo pi = pp.getEndPropertyInfo();

			Object objx = convert(jn, pi);
			if (objx == null) {
				hm.clear();
				break;
			}
			hm.put(fkeyName.toLowerCase(), objx);
		}

		OAObjectKey ok = null;
		if (hm.size() > 0) {
			ArrayList<Object> alKey = new ArrayList();
			for (OAFkeyInfo fi : li.getFkeyInfos()) {
				if (fi.getFromPropertyInfo() == null) {
					continue;
				}
				String s = fi.getFromPropertyInfo().getLowerName();
				Object objx = hm.get(s.toLowerCase());
				if (objx == null) {
					alPojoProperty.clear();
					break;
				}
				alKey.add(objx);
			}
			if (alKey.size() != 0) {
				Object[] objs = alKey.toArray(new Object[alKey.size()]);
				ok = new OAObjectKey(objs);
			}
		}

		OAObject obj = null;
		if (ok != null) {
			final OAGraph og =  OARuntime.graph(li.getToClass());
			obj = (OAObject) og.internal().objects().cache().get(li.getToClass(), ok);
			if (obj == null) {
				OADataSource ds = OARuntime.datasource().get(li.getToClass());
				if (ds != null) {
					obj = (OAObject) ds.getObject(li.getToClass(), ok);
				}
			}
		}
		if (obj == null && ok != null) {
			final OAGraph og =  OARuntime.graph(stackItem.obj);
			og.internal().objects().property().setProperty(stackItem.obj, li.getName(), ok);
		} else {
			stackItem.obj.setProperty(li.getName(), obj);
		}

		return true;
	}

	/**
	 * Attempts to resolve a POJO link-one reference using import-match key fields.
	 * <p>
	 * A SQL filter is constructed based on the POJO import-match properties. If all
	 * required JSON values are available, the loader searches the cache and then
	 * the data source. Missing references are queued for a retry.
	 *
	 * @param stackItem the current stack frame
	 * @param plo       POJO import-match metadata
	 * @param li        corresponding OA link metadata
	 * @return always {@code true} once processed
	 */
	protected boolean loadObjectPojoImportMatchReferences(final StackItem stackItem, final PojoLinkOne plo, final OALinkInfo li) {
		List<PojoProperty> alPojoProperty = PojoLinkOneDelegate.getImportMatchPojoProperties(plo);
		if (alPojoProperty == null || alPojoProperty.size() == 0) {
			return false;
		}

		String sql = null;
		Object[] values = new Object[] {};

		for (final PojoProperty pjp : alPojoProperty) {
			OAPath pp = new OAPath(stackItem.oi.getForClass(), pjp.getPropertyPath());
			OAPropertyInfo pi = pp.getEndPropertyInfo();

			JsonNode jn = stackItem.node.get(pjp.getName());
			Object val = convert(jn, pi);

			if (val == null) {
				sql = null;
				break;
			}

			if (sql == null) {
				sql = "";
			} else {
				sql += " AND ";
			}

			sql += OAString.field(pjp.getPropertyPath(), ".", 2, 99) + " = ?";
			values = OAArray.add(Object.class, values, val);
		}

		if (sql == null) {
			stackItem.obj.setProperty(li.getName(), null);
			return true;
		}

		OAFinder finder = new OAFinder();
		OAQueryFilter filter = new OAQueryFilter(li.getToClass(), sql, values);
		finder.addFilter(filter);
		final OAGraph og =  OARuntime.graph(li.getToClass());
		OAObject objNew = (OAObject) og.internal().objects().cache().find(li.getToClass(), finder);

		if (objNew == null) {
			OASelect sel = new OASelect(li.getToClass(), sql, values, "");
			objNew = sel.next();
			sel.close();
		}

		if (objNew == null) {
			RetryPojoReference rpr = new RetryPojoReference();
			rpr.stackItem = stackItem;
			rpr.plo = plo;
			rpr.li = li;
			getRetryPojoReferences().add(rpr);
		}

		stackItem.obj.setProperty(li.getName(), objNew);
		return true;
	}

	/**
	 * Attempts to resolve a POJO link-one reference using link-unique metadata.
	 * <p>
	 * A SQL query is built from unique and equal-property mappings. If no match is
	 * immediately found, the lookup is deferred and a {@link RetryPojoReference}
	 * entry is added.
	 *
	 * @param stackItem the current stack frame
	 * @param plo       POJO metadata defining link-unique rules
	 * @param li        OA link metadata for the reference
	 * @return {@code true} once processing is complete
	 */
	protected boolean loadObjectPojoUniqueReferences(final StackItem stackItem, final PojoLinkOne plo, final OALinkInfo li) {
		// link unique with equalPp
		List<PojoProperty> alPojoProperty = PojoLinkOneDelegate.getLinkUniquePojoProperties(plo);
		if (alPojoProperty == null || alPojoProperty.size() == 0) {
			return false;
		}

		String sql = null;
		Object[] values = new Object[] {};

		for (final PojoProperty pjp : alPojoProperty) {
			OAPath pp = new OAPath(stackItem.oi.getForClass(), pjp.getPropertyPath());
			OAPropertyInfo pi = pp.getEndPropertyInfo();

			JsonNode jn = stackItem.node.get(pjp.getName());
			Object val = convert(jn, pi);

			if (val == null) {
				sql = null;
				break;
			}

			if (sql == null) {
				sql = "";
			} else {
				sql += " AND ";
			}

			sql += OAString.field(pjp.getPropertyPath(), ".", 2, 99) + " = ?";
			values = OAArray.add(Object.class, values, val);
		}

		if (sql == null) {
			stackItem.obj.setProperty(li.getName(), null);
			return true;
		}

		EqualQueryForReference equalQuery = getEqualQueryForReference(stackItem, plo.getPojoLinkUnique());
		boolean bFound = false;
		if (equalQuery.value != null) {
			sql += " AND " + equalQuery.propPath + " = ?";
			values = OAArray.add(Object.class, values, equalQuery.value);

			OAFinder finder = new OAFinder();
			OAQueryFilter filter = new OAQueryFilter(li.getToClass(), sql, values);
			finder.addFilter(filter);
			final OAGraph og =  OARuntime.graph(li.getToClass());
			OAObject objNew = (OAObject) og.internal().objects().cache().find(li.getToClass(), finder);

			if (objNew == null) {
				OASelect sel = new OASelect(li.getToClass(), sql, values, "");
				objNew = sel.next();
				sel.close();
			}
			stackItem.obj.setProperty(li.getName(), objNew);
			bFound = (objNew != null);
		}

		if (!bFound) {
			RetryPojoReference rpr = new RetryPojoReference();
			rpr.stackItem = stackItem;
			rpr.plo = plo;
			rpr.li = li;
			getRetryPojoReferences().add(rpr);
		}

		return true;
	}

	/**
	 * Determines whether an existing object instance should be used rather than
	 * creating a new one.
	 * <p>
	 * In auto-create scenarios, the parent object may already contain the linked
	 * instance. Otherwise, the lookup is delegated to either
	 * {@link #_findExistingObject(OAJson.StackItem)} or
	 * {@link #_findExistingObjectFromPojo(OAJson.StackItem)} depending on mode.
	 *
	 * @param stackItem the stack frame describing the object to locate
	 */
	public void findExistingObject(final StackItem stackItem) {
		if (stackItem.li != null && stackItem.li.getAutoCreateNew() && stackItem.parent != null) {
			stackItem.obj = (OAObject) stackItem.li.getValue(stackItem.parent.obj);
			if (stackItem.obj != null) {
				return;
			}
		}
		if (!bUsesPojo) {
			_findExistingObject(stackItem);
		} else {
			_findExistingObjectFromPojo(stackItem);
		}
	}

	// NOTE: the same logic is also in _findExistingObjectPojo
	/**
	 * Attempts to find an existing OAObject using its ID properties.
	 * <p>
	 * JSON key values are mapped to identifier fields, converted, and used to
	 * search hubs, the object cache, and finally the data source. Compound and
	 * GUID-based keys are supported.
	 *
	 * @param stackItem the stack frame describing the object to locate
	 */
	protected void _findExistingObject(final StackItem stackItem) {
		final String[] keys = stackItem.oi.getIdProperties();
		final boolean bHasKey = keys != null && keys.length > 0;
		if (!bHasKey) {
			return;
		}

		final boolean bCompoundKey = keys.length > 1;

		String sql = null;
		Object[] args = new Object[0];

		if (stackItem.node.isObject()) {
			for (String key : keys) {
				OAPropertyInfo pi = stackItem.oi.getPropertyInfo(key);

				JsonNode jn = stackItem.node.get(stackItem.oi.getJsonUsesCapital() ? pi.getName() : pi.getLowerName());
				Object val = convert(jn, pi);

				if (val == null) {
					sql = null;
					break;
				}

				if (sql == null) {
					sql = "";
				} else {
					sql += " AND ";
				}

				sql += pi.getLowerName() + " = ?";
				args = OAArray.add(Object.class, args, val);
			}
		} else {
			if (stackItem.node.isTextual()) {
				String s = stackItem.node.asText();
				if (s != null && s.startsWith("guid.")) {
					OAObject objx = oajson.getGuidMap().get(UUID.fromString(s.substring(5)));
					stackItem.obj = objx;
					return;
				}
			}

			int pos = -1;
			for (String key : keys) {
				pos++;
				OAPropertyInfo pi = stackItem.oi.getPropertyInfo(key);

				Object val = stackItem.node.asText();
				if (bCompoundKey) {
					val = OAString.field((String) val, '-', pos + 1);
				}
				val = OAConv.convert(pi.getClassType(), val, null);

				if (val == null) {
					sql = null;
					break;
				}

				if (sql == null) {
					sql = "";
				} else {
					sql += " AND ";
				}

				sql += pi.getLowerName() + " = ?";
				args = OAArray.add(Object.class, args, val);
			}
		}

		// first, see if there is Hub to look in
		Hub hub = null;
		if (stackItem.li != null) {
			if (stackItem.li.isMany()) {
				hub = (Hub) stackItem.li.getValue(stackItem.parent.obj);
			} else {
				String pp = stackItem.li.getSelectFromPropertyPath();
				if (OAString.isNotEmpty(pp)) {
					OAPath ppx = new OAPath(stackItem.parent.oi.getForClass(), pp);
					hub = (Hub) ppx.getValue(stackItem.parent.obj);
				}
			}
		}

		if (sql == null) {
			if (hub == null) {
				return;
			}
			if (!stackItem.node.isObject()) {
				return;
			}
			if (OAString.isEmpty(stackItem.li.getUniqueProperty())) {
				return;
			}
		}

		if (hub != null) {
			OAFilter filter;
			if (sql != null) {
				filter = new OAQueryFilter(stackItem.li.getToClass(), sql, args);
			} else {
				String s = stackItem.li.getUniqueProperty();
				OAPropertyInfo pi = stackItem.oi.getPropertyInfo(s);
				if (pi == null) {
					return;
				}

				JsonNode jn = stackItem.node.get(s);
				Object val = convert(jn, pi);
				if (val == null) {
					return;
				}
				filter = new OAQueryFilter(stackItem.li.getToClass(), s + " = ?", new Object[] { val });
			}

			for (Object objx : hub) {
				if (filter.isUsed(objx)) {
					stackItem.obj = (OAObject) objx;
					break;
				}
			}
			if (stackItem.obj != null) {
				return;
			}
		}

		if (sql == null) {
			return;
		}

		// see if it's in objCache (since it might not be in DS)
		OADataSource ds = null;
		
		for (OADataSource dsx : OARuntime.datasource().getAll()) {
			if (dsx instanceof OADataSourceObjectCache) {
				ds = dsx;
				break;
			}
		}
		if (ds == null) {
			ds = new OADataSourceObjectCache(false);
			OARuntime.datasource().register(ds);
		}

		OADataSourceIterator dsi = ds.select(stackItem.oi.getForClass(), sql, args, null, false);
		Object objx = dsi.next();
		dsi.remove();

		if (objx == null) {
			OADataSource dsx = OARuntime.datasource().get(stackItem.oi.getForClass());
			if (dsx != null && dsx != ds) {
				OASelect sel = new OASelect(stackItem.oi.getForClass(), sql, args, null);
				objx = sel.next();
				sel.close();
			}
		}
		stackItem.obj = (OAObject) objx;
	}

	// NOTE: the same logic is also in _findExistingObject
	/**
	 * Attempts to locate an existing object when using POJO key rules.
	 * <p>
	 * Key resolution may use POJO primary keys, import-match keys, or link-unique
	 * keys. Matching objects are searched first in hubs, then the cache, and then
	 * the data source.
	 *
	 * @param stackItem the stack frame describing the object to locate
	 */
	protected void _findExistingObjectFromPojo(final StackItem stackItem) {
		final List<PojoProperty> alPojoProperyKeys = PojoDelegate.getPojoPropertyKeys(stackItem.oi.getPojo());
		final boolean bHasKey = alPojoProperyKeys != null && alPojoProperyKeys.size() > 0;
		final boolean bCompoundKey = bHasKey && alPojoProperyKeys.size() > 1;

		final boolean bUsesPKey = bHasKey && PojoDelegate.hasPkey(stackItem.oi);
		final boolean bUsesImportMatch = bHasKey && !bUsesPKey && PojoDelegate.hasImportMatchKey(stackItem.oi);
		final boolean bUseLinkUnique = bHasKey && !bUsesPKey && !bUsesImportMatch && PojoDelegate.hasLinkUniqueKey(stackItem.oi);

		String sql = null;
		Object[] args = new Object[0];

		if (stackItem.node.isObject()) {
			for (PojoProperty pojoProp : alPojoProperyKeys) {
				OAPath pp = new OAPath(stackItem.oi.getForClass(), pojoProp.getPropertyPath());
				OAPropertyInfo pi = pp.getEndPropertyInfo();

				JsonNode jn = stackItem.node.get(pojoProp.getName());
				Object val = convert(jn, pi);

				if (val == null) {
					sql = null;
					break;
				}

				if (sql == null) {
					sql = "";
				} else {
					sql += " AND ";
				}

				sql += pojoProp.getPropertyPath() + " = ?";
				args = OAArray.add(Object.class, args, val);
			}

		} else {
			int pos = -1;
			for (PojoProperty pojoProp : alPojoProperyKeys) {
				pos++;
				OAPath pp = new OAPath(stackItem.oi.getForClass(), pojoProp.getPropertyPath());
				OAPropertyInfo pi = pp.getEndPropertyInfo();

				Object val;

				val = stackItem.node.asText();
				if (bCompoundKey) {
					val = OAString.field((String) val, '-', pos + 1);
				}
				val = OAConv.convert(pi.getClassType(), val, null);

				if (val == null) {
					sql = null;
					break;
				}

				if (sql == null) {
					sql = "";
				} else {
					sql += " AND ";
				}

				sql += pojoProp.getPropertyPath() + " = ?";
				args = OAArray.add(Object.class, args, val);
			}
		}

		String sqlUnique = null;
		Object argUnique = null;

		EqualQueryForObject equalQuery = null;
		if (sql != null && bUseLinkUnique) {
			equalQuery = getEqualQueryForObject(stackItem);
			if (equalQuery.value != null) {
				sqlUnique = equalQuery.propPath + " = ?";
				argUnique = equalQuery.value;
			}
		}

		// first, see if there is Hub to look in
		Hub hub = null;
		if (stackItem.li != null) {
			if (stackItem.li.isMany()) {
				if (stackItem.parent != null) {
					hub = (Hub) stackItem.li.getValue(stackItem.parent.obj);
				}
			} else {
				String pp = stackItem.li.getSelectFromPropertyPath();
				if (OAString.isNotEmpty(pp)) {
					OAPath ppx = new OAPath(stackItem.oi.getForClass(), pp);
					hub = (Hub) ppx.getValue(stackItem.parent.obj);
				}
			}
		}

		if (sql == null) {
			if (hub == null) {
				return;
			}
			if (!stackItem.node.isObject()) {
				return;
			}
			if (OAString.isEmpty(stackItem.li.getUniqueProperty())) {
				return;
			}
		}

		if (hub != null) {
			OAFilter filter;
			if (sql != null) {
				filter = new OAQueryFilter(stackItem.li.getToClass(), sql, args);
			} else {
				String s = stackItem.li.getUniqueProperty();
				OAPropertyInfo pi = stackItem.oi.getPropertyInfo(s);
				if (pi == null) {
					return;
				}

				JsonNode jn = stackItem.node.get(s);
				Object val = convert(jn, pi);
				if (val == null) {
					return;
				}
				filter = new OAQueryFilter(stackItem.li.getToClass(), s + " = ?", new Object[] { val });
			}

			for (Object objx : hub) {
				if (filter.isUsed(objx)) {
					stackItem.obj = (OAObject) objx;
					return;
				}
			}
		}

		if (sql == null) {
			return;
		}

		if (sqlUnique != null) {
			args = OAArray.add(Object.class, args, argUnique);

			if (equalQuery != null && equalQuery.cntOrs > 0) {
				sqlUnique = "(" + sqlUnique + " OR " + equalQuery.sqlOrs + ")";
				for (int i = 0; i < equalQuery.cntOrs; i++) {
					args = OAArray.add(Object.class, args, equalQuery.value);
				}
			}
			sql += " AND " + sqlUnique;
		}

		// look in objectCache first
		OADataSource ds = null;
		for (OADataSource dsx : OARuntime.datasource().getAll()) {
			if (dsx instanceof OADataSourceObjectCache) {
				ds = dsx;
				break;
			}
		}
		if (ds == null) {
			ds = new OADataSourceObjectCache(false);
			OARuntime.datasource().register(ds);
		}

		if (debug) {
			System.out.println("SQL>>>> " + sql);
		}

		OADataSourceIterator dsi = ds.select(stackItem.oi.getForClass(), sql, args, null, false);
		Object objx = dsi.next();
		dsi.remove();
		
		if (objx == null) {
			OADataSource dsx = OARuntime.datasource().get(stackItem.oi.getForClass());
			if (dsx != null && dsx != ds) {
				OASelect sel = new OASelect(stackItem.oi.getForClass(), sql, args, null);
				objx = sel.next();
				sel.close();
			}
		}
		stackItem.obj = (OAObject) objx;
	}

	/**
	 * Converts a JSON node into a Java value for the given property.
	 * <p>
	 * Conversion rules consider name-value lists, numeric parsing, date/time
	 * formats, textual values, and fallback serialization of structured nodes.
	 *
	 * @param jn the JSON node containing the value
	 * @param pi metadata describing the target property
	 * @return the converted Java value, or {@code null} if none
	 */
	protected Object convert(final JsonNode jn, final OAPropertyInfo pi) {
		if (jn == null) {
			return null;
		}
		Object objx;
		if (jn.isNull()) {
			objx = null;
		} else {
			if (pi.isNameValue()) {  
				objx = jn.asText();
				if (objx != null) {
				    boolean bFound = false;
					for (int i = 0; i < pi.getVEnums().size(); i++) {
						String sx = ((VString) objx).getValue();
						if (sx != null && sx.equalsIgnoreCase(pi.getVEnums().get(i).getName())) {
							objx = i;
							bFound = true;
							break;
						}
					}
	                if (!bFound) {
	                    objx = OAConv.convert(Integer.class, objx, null);
	                }
				}
			} else if (jn.isNumber()) {
				objx = OAConv.convert(pi.getClassType(), jn.asText(), null);
			} else if (jn.isTextual()) {
				Class paramClass = pi.getClassType();
				String fmt = null;
				if (paramClass.equals(OADate.class)) {
					fmt = pi.getFormat();
					if (OAString.isEmpty(fmt)) {
						fmt = "yyyy-MM-dd";
					}
				} else if (paramClass.equals(OADateTime.class)) {
					fmt = pi.getFormat();
					if (OAString.isEmpty(fmt)) {
						fmt = "yyyy-MM-dd'T'HH:mm:ss";
					}
				} else if (paramClass.equals(OATime.class)) {
					fmt = pi.getFormat();
					if (OAString.isEmpty(fmt)) {
						fmt = "HH:mm:ss";
					}
				} else {
					fmt = pi.getFormat();
				}
				objx = OAConv.convert(pi.getClassType(), jn.asText(), fmt);
			} else {
				objx = OAConv.convert(pi.getClassType(), jn.toString()); //was: asText()
			}
		}
		return objx;
	}

	/**
	 * Holds parameters needed to build an equality query when resolving
	 * link-unique object references.
	 * <p>
	 * Includes the property path to compare, the resolved reference value, and
	 * optional OR-clause SQL fragments.
	 */
	protected static class EqualQueryForObject {
		String propPath;
		OAObject value;

		String sqlOrs;
		int cntOrs;
	}

	// build query based on Link that has unique and equalPp
	/**
	 * Builds query parameters for resolving link-unique references in non-POJO
	 * mode.
	 * <p>
	 * Determines the correct property path for equality comparison and resolves
	 * the reference object by analyzing the stack and link configuration.
	 *
	 * @param stackItem the stack frame describing the current object
	 * @return an {@link EqualQueryForObject} containing comparison data
	 */
	protected EqualQueryForObject getEqualQueryForObject(StackItem stackItem) {
		if (stackItem == null) {
			return null;
		}

		EqualQueryForObject eq = new EqualQueryForObject();

		eq.propPath = stackItem.li.getReverseLinkInfo().getEqualPropertyPath();
		if (OAString.isEmpty(eq.propPath)) {
			eq.propPath = stackItem.li.getReverseLinkInfo().getName();
		}

		final String ppEqualOrig = eq.propPath;

		for (OALinkInfo li : stackItem.oi.getLinkInfos()) {
			if (!li.isOne2Many()) {
				continue;
			}
			if (li == stackItem.li.getReverseLinkInfo()) {
				continue;
			}

			String s = li.getEqualPropertyPath();
			if (OAString.isEmpty(s)) {
				continue;
			}

			if (OAString.dcount(eq.propPath, '.') < OAString.dcount(s, '.')) {
				if (s.toLowerCase().startsWith(eq.propPath.toLowerCase())) {
					eq.propPath = s;
				}
			}
		}

		String ppFrom = stackItem.li.getEqualPropertyPath();
		if (OAString.isEmpty(ppFrom)) {
			ppFrom = "";
		}

		if (eq.propPath.length() > ppEqualOrig.length()) {
			String extra = eq.propPath.substring(ppEqualOrig.length() + 1);
			if (!OAString.isEmpty(ppFrom)) {
				ppFrom += ".";
			}
			ppFrom += extra;
		}

		OAPath pp = new OAPath(stackItem.li.getReverseLinkInfo().getToClass(), ppFrom);

		// see if any of the props in Pp can be skipped - if they are in stack
		int pos = 0;
		OALinkInfo[] lis = pp.getLinkInfos();
		StackItem si = stackItem.parent;
		for (; lis != null && pos < lis.length;) {
			if (lis[pos] != si.li.getReverseLinkInfo()) {
				break;
			}
			pos++;
			if (si.parent == null) {
				break;
			}
			si = si.parent;
		}

		if (lis != null && pos < lis.length) {
			eq.value = (OAObject) pp.getValue(si.obj, pos);
		} else if (si != null) {
			eq.value = si.obj;
		}

		// include other linkMany that it could be in
		for (OALinkInfo li : stackItem.oi.getLinkInfos()) {
			if (!li.isOne2Many()) {
				continue;
			}
			if (li == stackItem.li.getReverseLinkInfo()) {
				continue;
			}

			String s = li.getEqualPropertyPath();
			if (OAString.isEmpty(s)) {
				continue;
			}

			String sx = li.getReverseLinkInfo().getEqualPropertyPath();
			if (OAString.isEmpty(sx)) {
				sx = li.getName();
			} else {
				sx = li.getLowerName() + "." + sx;
			}

			if (eq.propPath.length() > li.getEqualPropertyPath().length()) {
				String extra = eq.propPath.substring(li.getEqualPropertyPath().length() + 1);
				if (!OAString.isEmpty(sx)) {
					sx += ".";
				}
				sx += extra;
			}

			if (eq.sqlOrs == null) {
				eq.sqlOrs = "";
			} else {
				eq.sqlOrs += " OR ";
			}
			eq.sqlOrs += sx + " = ?";

			eq.cntOrs++;
		}

		return eq;
	}

	/**
	 * Container for equality-query information used when resolving POJO link-unique
	 * references.
	 * <p>
	 * Includes the target property path, resolved reference object, and metadata
	 * needed to construct SQL OR-conditions.
	 */
	protected static class EqualQueryForReference {
		StackItem stackItem;
		PojoLinkUnique plu;
		String propPath;
		OAObject value;
	}

	// build query based on Link that has unique and equalPp
	/**
	 * Builds query parameters for resolving POJO link-unique relationships.
	 * <p>
	 * Determines the correct root object for comparison by analyzing equal-property
	 * paths and walking the stack hierarchy.
	 *
	 * @param stackItem the current stack frame
	 * @param plu       POJO link-unique metadata
	 * @return an {@link EqualQueryForReference} describing the match criteria
	 */
	protected EqualQueryForReference getEqualQueryForReference(final StackItem stackItem, final PojoLinkUnique plu) {
		if (stackItem == null || plu == null) {
			return null;
		}

		// String sx = stackItem.toString();
		//	sx += "=>" + plu.getPojoLinkOne().getPojoLink().getName();

		final EqualQueryForReference eq = new EqualQueryForReference();
		eq.stackItem = stackItem;
		eq.plu = plu;

		final OALinkInfo liToRef = stackItem.oi.getLinkInfo(plu.getPojoLinkOne().getPojoLink().getName());
		final OALinkInfo liFromRef = liToRef.getReverseLinkInfo();

		eq.propPath = liFromRef.getEqualPropertyPath();

		// get the root object used in equalPp
		String sppToMatch = liToRef.getEqualPropertyPath();
		OAPath pp = new OAPath(stackItem.oi.getForClass(), sppToMatch);

		// see if any of the props in ppx can be skipped - if they are in stack
		int pos = 0;
		OALinkInfo[] lis = pp.getLinkInfos();
		StackItem si = stackItem;
		for (; lis != null && pos < lis.length;) {
			if (si.li == null || lis[pos] != si.li.getReverseLinkInfo()) {
				break;
			}
			pos++;
			if (si.parent == null) {
				break;
			}
			si = si.parent;
		}

		if (lis != null && pos < lis.length) {
			eq.value = (OAObject) pp.getValue(si.obj, pos);
		} else if (si != null) {
			eq.value = si.obj;
		}
		if (eq.value != null) {
			return eq;
		}

		// find the root object using other links + equalPp
		for (OALinkInfo lix : stackItem.oi.getLinkInfos()) {
			if (!lix.isOne2Many()) {
				continue;
			}
			if (lix == liToRef) {
				continue;
			}

			String s = lix.getEqualPropertyPath();
			if (OAString.isEmpty(s)) {
				continue;
			}

			String extraPp = null;
			if (sppToMatch.toLowerCase().startsWith(s.toLowerCase())) {
				if (sppToMatch.length() > s.length()) {
					extraPp = sppToMatch.substring(s.length());
				} else {
					extraPp = "";
				}
			} else if (s.toLowerCase().startsWith(sppToMatch.toLowerCase())) {
				int x = OAString.dcount(s, ".") - OAString.dcount(sppToMatch, ".");
				OAPath ppx = new OAPath(stackItem.oi.getForClass(), s);
				ppx = ppx.getReversePath();
				if (ppx != null) {
    				OALinkInfo[] lisx = ppx.getLinkInfos();
    
    				for (int i = 0; i < x; i++) {
    					if (extraPp == null) {
    						extraPp = "";
    					}
    					extraPp += ".";
    					extraPp += lisx[i].getName();
    				}
				}
			} else {
				continue;
			}

			if (lix == stackItem.li.getReverseLinkInfo()) {
				String pps = lix.getReverseLinkInfo().getEqualPropertyPath() + extraPp;
				OAPath ppx = new OAPath(stackItem.parent.oi.getForClass(), pps);

				// see if any of the props in ppx can be skipped - if they are in stack
				pos = 0;
				lis = ppx.getLinkInfos();
				si = stackItem.parent;
				for (; lis != null && pos < lis.length;) {
					if (si.li == null || lis[pos] != si.li.getReverseLinkInfo()) {
						break;
					}
					pos++;
					if (si.parent == null) {
						break;
					}
					si = si.parent;
				}

				eq.value = (OAObject) ppx.getValue(si.obj, pos);
			} else {
				String pps = lix.getEqualPropertyPath() + extraPp;
				OAPath ppx = new OAPath(stackItem.oi.getForClass(), pps);
				eq.value = (OAObject) ppx.getValue(stackItem.obj);
			}
			if (eq.value != null) {
				break;
			}
		}
		return eq;
	}

	/**
	 * Records a POJO link-one reference that could not be resolved during the
	 * initial load pass.
	 * <p>
	 * Deferred references are retried after the object graph has been constructed.
	 */
	protected static class RetryPojoReference {
		StackItem stackItem;
		PojoLinkOne plo;
		OALinkInfo li;
	}

	/**
	 * Retries all deferred POJO link-one references.
	 * <p>
	 * Each entry is processed using import-match rules first, followed by
	 * link-unique rules if necessary.
	 */
	protected void retryPojoReferences() {
		List<RetryPojoReference> al = new ArrayList();
		al.addAll(getRetryPojoReferences());
		getRetryPojoReferences().clear();
		for (RetryPojoReference rpr : al) {
			if (!loadObjectPojoImportMatchReferences(rpr.stackItem, rpr.plo, rpr.li)) {
				loadObjectPojoUniqueReferences(rpr.stackItem, rpr.plo, rpr.li);
			}
		}
	}

	/**
	 * Returns the list of POJO references that could not be resolved immediately.
	 *
	 * @return the modifiable retry-reference list
	 */
	public List<RetryPojoReference> getRetryPojoReferences() {
		return alRetryPojoReference;
	}

	/**
	 * Enables or disables debug tracing for the loader.
	 *
	 * @param b {@code true} to enable debug output, {@code false} to disable it
	 */
	public void setDebug(boolean b) {
		this.debug = b;
	}

	/**
	 * Indicates whether debug tracing is currently enabled.
	 *
	 * @return {@code true} if debug mode is active, otherwise {@code false}
	 */
	public boolean getDebug() {
		return this.debug;
	}

	/**
	 * Emits a debug message including the object path for the given stack frame.
	 * <p>
	 * Shows object hierarchy and indentation when debug mode is enabled.
	 *
	 * @param si  the stack frame associated with the message
	 * @param msg the debug message text
	 */
	public void debug(StackItem si, String msg) {
		debug(si, true, msg);
	}

	/**
	 * Emits a debug message without including object-path information.
	 *
	 * @param si  the stack frame associated with the message
	 * @param msg the debug message text
	 */
	protected void debug2(StackItem si, String msg) {
		debug(si, false, msg);
	}

	/**
	 * Internal helper for emitting formatted debug messages.
	 * <p>
	 * Based on the {@code bShowObj} flag, includes or omits object-path display.
	 * Indentation is derived from stack depth.
	 *
	 * @param si        the stack frame to inspect
	 * @param bShowObj  whether to include object-path information
	 * @param msg       the debug message text
	 */
	protected void debug(StackItem si, boolean bShowObj, String msg) {
		if (!debug) {
			return;
		}

		String objpath = "";
		int indent = -1;
		for (; si != null; si = si.parent) {
			String s2 = si.oi.getName();
			if (si.li != null) {
				s2 = si.li.getLowerName();
				// s2 = si.li.getLowerName() + " (" + s2 + ")";
			}

			if (objpath.length() > 0) {
				objpath = " => " + objpath;
			}
			objpath = s2 + objpath;

			/*
			if (objpath.length() == 0) {
				objpath = s2;
			}
			*/
			++indent;
		}

		String prefix = "";
		for (int i = 0; i < indent; i++) {
			prefix += "| ";
		}
		if (!bShowObj) {
			objpath = "";
			prefix += "|     ";
		}
		if (OAString.isEmpty(msg)) {
			msg = "";
		} else {
			msg += " ";
		}
		System.out.println(prefix + msg + objpath);
	}
}
