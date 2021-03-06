/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 *  Copyright 2013 Josh Elser
 *
 */
package cosmos.results;

import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.DataOutputBuffer;
import org.junit.Assert;
import org.junit.Test;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

import cosmos.results.impl.MultimapQueryResult;

/**
 * 
 */
public class MultimapQueryResultTest extends AbstractSortableTest {
  
  @Test
  public void identityWritableEquality() throws Exception {
    Multimap<Column,SValue> data = HashMultimap.create();
    
    data.put(Column.create("TEXT"), SValue.create("foo", VIZ));
    data.put(Column.create("TEXT"), SValue.create("bar", VIZ));
    
    MultimapQueryResult mqr = new MultimapQueryResult(data, "1", VIZ);
    
    DataOutputBuffer out = new DataOutputBuffer();
    mqr.write(out);
    
    DataInputBuffer in = new DataInputBuffer();
    
    byte[] bytes = out.getData();
    in.reset(bytes, out.getLength());
    
    MultimapQueryResult mqr2 = MultimapQueryResult.recreate(in);
    
    Assert.assertEquals(mqr, mqr2);
  }
  
  @Test
  public void nonEqual() throws Exception {
    Multimap<Column,SValue> data = HashMultimap.create();
    
    data.put(Column.create("TEXT"), SValue.create("foo", VIZ));
    data.put(Column.create("TEXT"), SValue.create("bar", VIZ));
    
    MultimapQueryResult mqr = new MultimapQueryResult(data, "1", VIZ);
    MultimapQueryResult mqr2 = new MultimapQueryResult(data, "2", VIZ);
    
    Assert.assertNotEquals(mqr, mqr2);
    
    MultimapQueryResult mqr3 = new MultimapQueryResult(data, "1", new ColumnVisibility("foobarbarbarbarbarbar"));
    
    Assert.assertNotEquals(mqr, mqr3);
    Assert.assertNotEquals(mqr2, mqr3);
    
    data = HashMultimap.create(data);
    
    data.put(Column.create("FOO"), SValue.create("barfoo", VIZ));
    
    MultimapQueryResult mqr4 = new MultimapQueryResult(data, "1", VIZ);
    
    Assert.assertNotEquals(mqr, mqr4);
    Assert.assertNotEquals(mqr2, mqr4);
    Assert.assertNotEquals(mqr3, mqr4);
  }
  
}
