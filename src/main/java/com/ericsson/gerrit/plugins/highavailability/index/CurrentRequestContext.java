// Copyright (C) 2019 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.ericsson.gerrit.plugins.highavailability.index;

import com.google.gerrit.server.util.FallbackRequestContext;
import com.google.gerrit.server.util.RequestContext;
import com.google.gerrit.server.util.ThreadLocalRequestContext;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.function.Consumer;

@Singleton
public class CurrentRequestContext {
  private ThreadLocalRequestContext threadLocalCtx;

  @Inject
  public CurrentRequestContext(ThreadLocalRequestContext threadLocalCtx) {
    this.threadLocalCtx = threadLocalCtx;
  }

  public void whenActive(Consumer<RequestContext> body) {
    RequestContext ctx = currentRequestContext();
    if (ctx == null) {
      return;
    }

    body.accept(ctx);
  }

  private RequestContext currentRequestContext() {
    RequestContext requestContext = threadLocalCtx.getContext();
    return (requestContext instanceof FallbackRequestContext) ? null : requestContext;
  }
}
