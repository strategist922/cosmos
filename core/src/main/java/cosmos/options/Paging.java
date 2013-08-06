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
package cosmos.options;
import com.google.common.base.Preconditions;


public class Paging {
  protected final Integer pageSize;
  protected final Long maxResults;
  
  public Paging(Integer pageSize, Long maxResults) {
    Preconditions.checkNotNull(pageSize);
    Preconditions.checkNotNull(maxResults);
    
    Preconditions.checkArgument(0 < pageSize);
    Preconditions.checkArgument(0 < maxResults);
    
    this.pageSize = pageSize;
    this.maxResults = maxResults;
  }
  
  /**
   * @return the pageSize
   */
  public Integer pageSize() {
    return pageSize;
  }

  /**
   * @return the maxResults
   */
  public Long maxResults() {
    return maxResults;
  }
 
  public static Paging create(Integer pageSize, Long maxResults) {
    return new Paging(pageSize, maxResults);
  }
}
