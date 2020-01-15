/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import org.atomos.framework.AtomosRuntime;
module service.image {
	requires atomos.framework;
	requires service.impl;
	requires service.impl.activator;
	requires org.apache.felix.scr;
	requires org.apache.felix.gogo.command;
	requires org.apache.felix.gogo.runtime;
	requires org.apache.felix.gogo.shell;
	requires jdk.jdwp.agent;
	uses AtomosRuntime;
}
