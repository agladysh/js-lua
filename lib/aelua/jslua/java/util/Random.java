/*
 * Copyright 2010 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package java.util;

public class Random {

  protected int next(int bits) {
    return com.google.gwt.user.client.Random.nextInt();
  }

  public int nextInt() {
    return com.google.gwt.user.client.Random.nextInt();
  }

  public int nextInt(int n) {
    return com.google.gwt.user.client.Random.nextInt(n);
  }

  public long nextLong() {
    return 0;
  }
  public void setSeed(long l) {
  }

  public boolean nextBoolean() {
    return com.google.gwt.user.client.Random.nextBoolean();
  }

  public float nextFloat() {
    return (float) com.google.gwt.user.client.Random.nextDouble();
  }

  public double nextDouble() {
    return com.google.gwt.user.client.Random.nextDouble();
  }
}
