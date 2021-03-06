/*
 * This file is part of Sponge, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.common.inject;

import com.google.inject.Binder;
import com.google.inject.Binding;
import com.google.inject.Module;
import com.google.inject.Provider;
import com.google.inject.matcher.AbstractMatcher;
import com.google.inject.spi.Dependency;
import com.google.inject.spi.DependencyAndSource;
import com.google.inject.spi.InjectionPoint;
import com.google.inject.spi.ProviderInstanceBinding;
import com.google.inject.spi.ProvisionListener;

import java.util.List;

import javax.annotation.Nullable;

/**
 * Allows injecting the {@link InjectionPoint} in {@link Provider}s.
 */
public final class InjectionPointProvider extends AbstractMatcher<Binding<?>> implements Module, ProvisionListener, Provider<InjectionPoint> {

    @Nullable private InjectionPoint injectionPoint;

    @Override
    public InjectionPoint get() {
        return this.injectionPoint;
    }

    @Override
    public boolean matches(Binding<?> binding) {
        return binding instanceof ProviderInstanceBinding && ((ProviderInstanceBinding) binding).getUserSuppliedProvider() == this;
    }

    @Override
    public <T> void onProvision(ProvisionInvocation<T> provision) {
        try {
            this.injectionPoint = findInjectionPoint(provision.getDependencyChain());
            provision.provision();
        } finally {
            this.injectionPoint = null;
        }
    }

    private static InjectionPoint findInjectionPoint(List<DependencyAndSource> dependencyChain) {
        if (dependencyChain.size() < 3) {
            throw new AssertionError("Provider is not included in the dependency chain");
        }

        // @Inject InjectionPoint is the last, so we can skip it
        for (int i = dependencyChain.size() - 2; i >= 0; i--) {
            Dependency<?> dependency = dependencyChain.get(i).getDependency();
            if (dependency == null) {
                return null;
            }

            InjectionPoint injectionPoint = dependency.getInjectionPoint();
            if (injectionPoint != null) {
                return injectionPoint;
            }
        }

        return null;
    }

    @Override
    public void configure(Binder binder) {
        binder.bind(InjectionPoint.class).toProvider(this);
        binder.bindListener(this, this);
    }

}
