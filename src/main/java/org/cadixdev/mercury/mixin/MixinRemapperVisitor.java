/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.cadixdev.mercury.mixin;

import static org.cadixdev.mercury.mixin.util.MixinConstants.ACCESSOR_CLASS;
import static org.cadixdev.mercury.mixin.util.MixinConstants.OVERWRITE_CLASS;
import static org.cadixdev.mercury.mixin.util.MixinConstants.SHADOW_CLASS;
import static org.cadixdev.mercury.util.BombeBindings.convertType;

import org.cadixdev.bombe.analysis.InheritanceProvider;
import org.cadixdev.bombe.type.FieldType;
import org.cadixdev.bombe.type.MethodDescriptor;
import org.cadixdev.bombe.type.signature.FieldSignature;
import org.cadixdev.bombe.type.signature.MethodSignature;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.model.ClassMapping;
import org.cadixdev.lorenz.model.FieldMapping;
import org.cadixdev.lorenz.model.MethodMapping;
import org.cadixdev.mercury.RewriteContext;
import org.cadixdev.mercury.analysis.MercuryInheritanceProvider;
import org.cadixdev.mercury.mixin.annotation.AccessorName;
import org.cadixdev.mercury.mixin.annotation.MixinData;
import org.cadixdev.mercury.mixin.annotation.OverwriteData;
import org.cadixdev.mercury.mixin.annotation.ShadowData;
import org.cadixdev.mercury.util.BombeBindings;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.SimpleName;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MixinRemapperVisitor extends ASTVisitor {

    final RewriteContext context;
    final MappingSet mappings;
    private final InheritanceProvider inheritanceProvider;

    MixinRemapperVisitor(final RewriteContext context, final MappingSet mappings) {
        this.context = context;
        this.mappings = mappings;
        this.inheritanceProvider = MercuryInheritanceProvider.get(context.getMercury());
    }

    void remapField(final SimpleName node, final IVariableBinding binding) {
        if (!binding.isField()) return;

        final ITypeBinding declaringClass = binding.getDeclaringClass();
        final MixinData mixin = MixinData.fetch(declaringClass);
        if (mixin == null) return;

        // todo: support multiple targets properly
        final ClassMapping<?, ?> target = this.mappings.computeClassMapping(mixin.getTargets()[0].getBinaryName()).orElse(null);
        if (target == null) return;

        for (final IAnnotationBinding annotation : binding.getAnnotations()) {
            final String annotationType = annotation.getAnnotationType().getBinaryName();

            // @Shadow
            if (Objects.equals(SHADOW_CLASS, annotationType)) {
                final ShadowData shadow = ShadowData.from(annotation);

                final boolean usedPrefix = binding.getName().startsWith(shadow.getPrefix());
                final FieldSignature targetSignature = convertSignature(shadow.stripPrefix(binding.getName()), binding.getType());
                final FieldSignature mixinSignature = BombeBindings.convertSignature(binding);

                // Get mapping of target field
                final FieldMapping targetField = target.computeFieldMapping(targetSignature).orElse(null);
                if (targetField != null) {
                    final String deobfName = targetField.getDeobfuscatedName();

                    // Create mapping for mixin
                    final ClassMapping<?, ?> classMapping = this.mappings.getOrCreateClassMapping(declaringClass.getBinaryName());
                    final FieldMapping field = classMapping.getOrCreateFieldMapping(mixinSignature);
                    field.setDeobfuscatedName(usedPrefix ? shadow.prefix(deobfName) : deobfName);
                }
            }
        }
    }

    private void remapMethod(final SimpleName node, final IMethodBinding binding) {
        if (binding.isConstructor()) return;

        final ITypeBinding declaringClass = binding.getDeclaringClass();
        final MixinData mixin = MixinData.fetch(declaringClass);
        if (mixin == null) return;

        // todo: support multiple targets properly
        final ClassMapping<?, ?> target = this.mappings.computeClassMapping(mixin.getTargets()[0].getBinaryName()).orElse(null);
        if (target == null) return;
        target.complete(this.inheritanceProvider, declaringClass);

        for (final IAnnotationBinding annotation : binding.getAnnotations()) {
            final String annotationType = annotation.getAnnotationType().getBinaryName();

            // @Shadow
            if (Objects.equals(SHADOW_CLASS, annotationType)) {
                final ShadowData shadow = ShadowData.from(annotation);

                final boolean usedPrefix = binding.getName().startsWith(shadow.getPrefix());
                final MethodSignature targetSignature = convertSignature(shadow.stripPrefix(binding.getName()), binding);
                final MethodSignature mixinSignature = BombeBindings.convertSignature(binding);

                // Get mapping of target method
                final MethodMapping targetMethod = target.getMethodMapping(targetSignature).orElse(null);
                if (targetMethod != null) {
                    final String deobfName = targetMethod.getDeobfuscatedName();

                    // Create mapping for mixin
                    final ClassMapping<?, ?> classMapping = this.mappings.getOrCreateClassMapping(declaringClass.getBinaryName());
                    final MethodMapping field = classMapping.getOrCreateMethodMapping(mixinSignature);
                    field.setDeobfuscatedName(usedPrefix ? shadow.prefix(deobfName) : deobfName);
                }
            }

            // @Overwrite
            if (Objects.equals(OVERWRITE_CLASS, annotationType)) {
                final OverwriteData overwrite = OverwriteData.from(annotation);

                final MethodSignature signature = BombeBindings.convertSignature(binding);

                // Get mapping of target method
                final MethodMapping targetMethod = target.getMethodMapping(signature).orElse(null);
                if (targetMethod != null) {
                    final String deobfName = targetMethod.getDeobfuscatedName();

                    // Create mapping for mixin
                    final ClassMapping<?, ?> classMapping = this.mappings.getOrCreateClassMapping(declaringClass.getBinaryName());
                    final MethodMapping method = classMapping.getOrCreateMethodMapping(signature);
                    method.setDeobfuscatedName(deobfName);
                }
            }

            // @Accessor
            if (Objects.equals(ACCESSOR_CLASS, annotationType)) {
                final AccessorName name = AccessorName.of(binding.getName());

                final MethodSignature mixinSignature = BombeBindings.convertSignature(binding);

                // Getters
                if (Objects.equals("get", name.getPrefix()) || Objects.equals("is", name.getPrefix())) {
                    final FieldSignature targetSignature = new FieldSignature(name.getName(), (FieldType) mixinSignature.getDescriptor().getReturnType());

                    // Get mapping of target field
                    final FieldMapping targetField = target.computeFieldMapping(targetSignature).orElse(null);
                    if (targetField != null) {
                        final String deobfName = targetField.getDeobfuscatedName();

                        // Create mapping for mixin
                        final ClassMapping<?, ?> classMapping = this.mappings.getOrCreateClassMapping(declaringClass.getBinaryName());
                        final MethodMapping method = classMapping.getOrCreateMethodMapping(mixinSignature);
                        method.setDeobfuscatedName(name.prefix(deobfName));
                    }
                }

                // Setters
                if (Objects.equals("set", name.getPrefix())) {
                    final FieldSignature targetSignature = new FieldSignature(name.getName(), mixinSignature.getDescriptor().getParamTypes().get(0));

                    // Get mapping of target field
                    final FieldMapping targetField = target.computeFieldMapping(targetSignature).orElse(null);
                    if (targetField != null) {
                        final String deobfName = targetField.getDeobfuscatedName();

                        // Create mapping for mixin
                        final ClassMapping<?, ?> classMapping = this.mappings.getOrCreateClassMapping(declaringClass.getBinaryName());
                        final MethodMapping method = classMapping.getOrCreateMethodMapping(mixinSignature);
                        method.setDeobfuscatedName(name.prefix(deobfName));
                    }
                }
            }
        }
    }

    private void visit(final SimpleName node, final IBinding binding) {
        switch (binding.getKind()) {
            case IBinding.VARIABLE:
                remapField(node, ((IVariableBinding) binding).getVariableDeclaration());
                break;
            case IBinding.METHOD:
                remapMethod(node, ((IMethodBinding) binding).getMethodDeclaration());
                break;
        }
    }

    @Override
    public final boolean visit(final SimpleName node) {
        final IBinding binding = node.resolveBinding();
        if (binding != null) {
            visit(node, binding);
        }
        return false;
    }

    private static FieldSignature convertSignature(final String name, final ITypeBinding type) {
        return new FieldSignature(name, (FieldType) convertType(type));
    }

    private static MethodSignature convertSignature(final String name, final IMethodBinding binding) {
        final ITypeBinding[] parameterBindings = binding.getParameterTypes();
        final List<FieldType> parameters = new ArrayList<>(parameterBindings.length);

        for (final ITypeBinding parameterBinding : parameterBindings) {
            parameters.add((FieldType) convertType(parameterBinding));
        }

        return new MethodSignature(name, new MethodDescriptor(parameters, convertType(binding.getReturnType())));
    }

}
