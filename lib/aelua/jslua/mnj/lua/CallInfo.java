/*  $Header: //info.ravenbrook.com/project/jili/version/1.1/code/mnj/lua/CallInfo.java#1 $
 * Copyright (c) 2006 Nokia Corporation and/or its subsidiary(-ies).
 * All rights reserved.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject
 * to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
 * ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package mnj.lua;

final class CallInfo
{
  private int savedpc;
  private int func;
  private int base;
  private int top;
  private int nresults;
  private int tailcalls;

  /** Only used to create the first instance. */
  CallInfo()
  {
  }

  /**
   * @param func  stack index of function
   * @param base  stack base for this frame
   * @param top   top-of-stack for this frame
   * @param nresults  number of results expected by caller
   */
  CallInfo(int func, int base, int top, int nresults)
  {
    this.func = func;
    this.base = base;
    this.top = top;
    this.nresults = nresults;
  }

  /** Setter for savedpc. */
  void setSavedpc(int pc)
  {
    savedpc = pc;
  }
  /** Getter for savedpc. */
  int savedpc()
  {
    return savedpc;
  }

  /**
   * Get the stack index for the function object for this record.
   */
  int function()
  {
    return func;
  }

  /**
   * Get stack index where results should end up.  This is an absolute
   * stack index, not relative to L.base.
   */
  int res()
  {
    // Same location as function.
    return func;
  }

  /**
   * Get stack base for this record.
   */
  int base()
  {
    return base;
  }

  /**
   * Get top-of-stack for this record.  This is the number of elements
   * in the stack (or will be when the function is resumed).
   */
  int top()
  {
    return top;
  }

  /**
   * Setter for top.
   */
  void setTop(int top)
  {
    this.top = top;
  }

  /**
   * Get number of results expected by the caller of this function.
   * Used to adjust the returned results to the correct number.
   */
  int nresults()
  {
    return nresults;
  }

  /**
   * Get number of tailcalls
   */
  int tailcalls()
  {
    return tailcalls;
  }

  /**
   * Used during tailcall to set the base and top members.
   */
  void tailcall(int baseArg, int topArg)
  {
    this.base = baseArg;
    this.top = topArg;
    ++tailcalls;
  }
}
