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
/**
 * Provides JSON serialization and deserialization support for OAObject-based
 * models, Hubs, and related OA framework components. This package builds on
 * Jackson but applies OA’s identity, reference, and property-path rules so
 * that JSON can represent a complete, reconnectable OAObjectGraph.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Serialize OAObjects using OAObjectKey, GUIDs, and property paths.</li>
 *   <li>Deserialize JSON back into existing instances when possible, preserving
 *       object identity, Hub membership, and reference wiring.</li>
 *   <li>Support ImportMatch logic for POJO data where primary keys are absent.</li>
 *   <li>Handle Hub serialization (arrays, object references, and IDs).</li>
 *   <li>Convert remote-method arguments to/from JSON with type hints.</li>
 *   <li>Respect OACascade and OAPropertyPath rules during serialization.</li>
 *   <li>Provide a clean JSON bridge for REST, remote invocation, and
 *       distributed messaging layers.</li>
 * </ul>
 *
 * <h2>Design Principles</h2>
 * <ul>
 *   <li>Does not use reflection for OAObject field access; relies on
 *       OAPropertyPath and OAObjectDelegate.</li>
 *   <li>Ensures that generated JSON can be safely reloaded into an active
 *       OAObjectGraph with full identity preservation.</li>
 *   <li>Supports partial serialization (filters) and deep serialization
 *       (nested property paths).</li>
 *   <li>Thread-local integration ensures correct behavior during Jackson
 *       serializer/deserializer callbacks.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <p>
 * Clients typically instantiate {@link com.viaoa.json.OAJson} directly.
 * Most OA remoting and REST components use this package internally.
 * </p>
 *
 * @author vvia
 */
package com.viaoa.json;
