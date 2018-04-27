/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.jobgraph;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests to guard {@link SavepointRestoreSettings}.
 */
public class SavepointRestoreSettingsTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void testGetRestorePath() {

		// test for savepoint
		{
			SavepointRestoreSettings savepointRestoreSettings = SavepointRestoreSettings.forPath("savepointPath");
			Assert.assertEquals(SavepointRestoreSettings.RestoreType.FROM_SAVEPOINT, savepointRestoreSettings.getRestoreType());
			Assert.assertEquals("savepointPath", savepointRestoreSettings.getRestorePath());
		}

		// test for externalized checkpoint
		{

		}
	}
}
