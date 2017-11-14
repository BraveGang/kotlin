/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.codegen.optimization

import org.jetbrains.kotlin.codegen.inline.ReifiedTypeInliner
import org.jetbrains.kotlin.codegen.optimization.common.OptimizationBasicInterpreter
import org.jetbrains.kotlin.codegen.optimization.fixStack.top
import org.jetbrains.kotlin.codegen.optimization.transformer.MethodTransformer
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.findClassAcrossModuleDependencies
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.DescriptorUtils.isSubclass
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.tree.MethodNode
import org.jetbrains.org.objectweb.asm.tree.TypeInsnNode

class RedundantCheckCastEliminationMethodTransformer(
        private val moduleDescriptor: ModuleDescriptor
) : MethodTransformer() {

    override fun transform(internalClassName: String, methodNode: MethodNode) {
        val insns = methodNode.instructions.toArray()
        if (!insns.any { it.opcode == Opcodes.CHECKCAST }) return

        val redundantCheckCasts = ArrayList<TypeInsnNode>()

        val frames = analyze(internalClassName, methodNode, OptimizationBasicInterpreter())
        for (i in insns.indices) {
            val valueType = frames[i]?.top()?.type ?: continue
            val insn = insns[i]
            if (ReifiedTypeInliner.isOperationReifiedMarker(insn.previous)) continue

            if (insn is TypeInsnNode && insn.opcode == Opcodes.CHECKCAST) {
                val insnType = Type.getObjectType(insn.desc)
                if (insnType.sort != Type.OBJECT) continue
                if (isSubtype(valueType, insnType)) {
                    redundantCheckCasts.add(insn)
                }
            }
        }

        redundantCheckCasts.forEach {
            methodNode.instructions.remove(it)
        }
    }

    private fun isSubtype(subType: Type, superType: Type): Boolean {
        if (superType == subType) return true

        val superClassDescriptor = resolveClassDescriptor(superType) ?: return false
        val subClassDescriptor = resolveClassDescriptor(subType) ?: return false

        return isSubclass(subClassDescriptor, superClassDescriptor)
    }

    private fun resolveClassDescriptor(type: Type): ClassDescriptor? {
        if (type.sort != Type.OBJECT) return null

        val topLevelFqNameString = type.className.replace('$', '.')
        return moduleDescriptor.findClassAcrossModuleDependencies(ClassId.topLevel(FqName(topLevelFqNameString)))
    }

}