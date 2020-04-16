// BSD 3-Clause License
//
// Copyright (c) 2020, Scott Petersen
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// 1. Redistributions of source code must retain the above copyright notice, this
//    list of conditions and the following disclaimer.
//
// 2. Redistributions in binary form must reproduce the above copyright notice,
//    this list of conditions and the following disclaimer in the documentation
//    and/or other materials provided with the distribution.
//
// 3. Neither the name of the copyright holder nor the names of its
//    contributors may be used to endorse or promote products derived from
//    this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
// FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
// DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
// SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
// CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
// OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package io.jart.pojo;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.objectweb.asm.*;

public class Helper implements Opcodes {
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	public @interface POJO { // apply to POJO class
		public String[] fieldOrder();
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	public @interface NoClear {} // apply to a field that shouldn't be cleared on free

	public static final Helper defaultHelper = new Helper();
	public static final Consumer<?> noopConsumer = (Object)->{};
	
	private final Loader loader;

	public Helper() {
		loader = new Loader(ClassLoader.getSystemClassLoader());
	}

	public Helper(ClassLoader parent) {
		loader = new Loader(parent);
	}

	public<T> Supplier<T> newConstructingSupplier(Class<T> baseClass) throws ClassNotFoundException, InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException {
		Class<?> pojoCSClass = loader.loadClass(baseClass.getTypeName() + "$$POJO$ConstructingSupplier");
		@SuppressWarnings("unchecked")
		Constructor<Supplier<T>> constructor = (Constructor<Supplier<T>>) pojoCSClass.getConstructor();

		return constructor.newInstance();
	}

	public<T> Object newAlloc(Class<T> baseClass, Supplier<T> supplier, Consumer<T> consumer) throws ClassNotFoundException, NoSuchMethodException, SecurityException, InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		Class<?> allocImplClass = loader.loadClass(baseClass.getTypeName() + "$$POJO$Alloc");
		Constructor<?> constructor = allocImplClass.getConstructor(Supplier.class, Consumer.class); 
		
		return constructor.newInstance(supplier, consumer);
	}

	@SuppressWarnings("unchecked")
	public<T> Object newTrivialAlloc(Class<T> baseClass) throws ClassNotFoundException, NoSuchMethodException, SecurityException, InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		Class<?> allocImplClass = loader.loadClass(baseClass.getTypeName() + "$$POJO$Alloc$NoClear");
		Constructor<?> constructor = allocImplClass.getConstructor(Supplier.class, Consumer.class); 
		
		return constructor.newInstance(newConstructingSupplier(baseClass), (Consumer<T>)noopConsumer);
	}
	
	private static class Loader extends ClassLoader {
		public Loader(ClassLoader cl) {
			super(cl);
		}
		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			if(name.endsWith("$$POJO")) {
				String baseName = name.substring(0, name.length() - 6);
				Class<?> baseClass = loadClass(baseName);
				
				try {
					byte[] classBytes = createPOJOClassBytes(name.replace('.', '/'), baseClass);
					
					return defineClass(name, classBytes, 0, classBytes.length);
				} catch (SecurityException e) {
					throw new ClassNotFoundException("error creating class", e);
				}
			}
			else if(name.endsWith("$$POJO$ConstructingSupplier")) {
				String pojoName = name.substring(0, name.length() - 21);
				byte[] classBytes = createConstructingSupplierClassBytes(name.replace('.', '/'), pojoName.replace('.', '/'));

				return defineClass(name, classBytes, 0, classBytes.length);
			}
			else if(name.endsWith("$$POJO$Alloc") || name.endsWith("$$POJO$Alloc$NoClear")) {
				String pojoName = name.substring(0, name.length() - (name.endsWith("$NoClear") ? 14 : 6));
				String baseName = pojoName.substring(0, pojoName.length() - 6);
				Class<?> baseClass = loadClass(baseName);

				try {
					byte[] classBytes = createAllocClassBytes(name.replace('.', '/'), pojoName.replace('.', '/'), baseClass);
	
					return defineClass(name, classBytes, 0, classBytes.length);
				} catch (SecurityException e) {
					throw new ClassNotFoundException("error creating class", e);
				}
			}
			return super.findClass(name);
		}

		private static Field[] getFields(Class<?> clazz) throws SecurityException, ClassNotFoundException {
			POJO pojoAnnot = clazz.getAnnotation(POJO.class);

			if(pojoAnnot == null)
				throw new ClassNotFoundException("can't find POJO annot");

			String[] fieldOrder = pojoAnnot.fieldOrder();
			Field[] result = new Field[fieldOrder.length];
			
			for(int i = 0; i < fieldOrder.length; i++) {
				Class<?> curClass = clazz;
				
				for(;;) {
					try {
						result[i] = curClass.getDeclaredField(fieldOrder[i]);
						break;
					}
					catch(NoSuchFieldException e) {
						curClass = curClass.getSuperclass();
					}
				}
			}
			
			return result;
		}
		
		private byte[] createAllocClassBytes (String name, String pojoName, Class<?> baseClass) throws ClassNotFoundException, SecurityException {
			Type baseType = Type.getType(baseClass);
			String baseDesc = baseType.getDescriptor();
			String baseName = baseClass.getTypeName().replace('.',  '/');
			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			FieldVisitor fv;
			MethodVisitor mv;

			cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, name, null, "java/lang/Object", new String[] { baseName + "$Alloc" });

			{
				fv = cw.visitField(ACC_PRIVATE + ACC_FINAL, "supplier", "Ljava/util/function/Supplier;", null, null);
				fv.visitEnd();
			}
			{
				fv = cw.visitField(ACC_PRIVATE + ACC_FINAL, "consumer", "Ljava/util/function/Consumer;", null, null);
				fv.visitEnd();
			}
			{
				mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/util/function/Supplier;Ljava/util/function/Consumer;)V", null, null);
				mv.visitCode();

				mv.visitVarInsn(ALOAD, 0);
				mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);

				mv.visitVarInsn(ALOAD, 0);
				mv.visitVarInsn(ALOAD, 1);
				mv.visitFieldInsn(PUTFIELD, name, "supplier", "Ljava/util/function/Supplier;");
				mv.visitVarInsn(ALOAD, 0);
				mv.visitVarInsn(ALOAD, 2);
				mv.visitFieldInsn(PUTFIELD, name, "consumer", "Ljava/util/function/Consumer;");

				mv.visitInsn(RETURN);
				
				mv.visitMaxs(0, 0);
				mv.visitEnd();
			}
			{
				Field[] fields = getFields(baseClass);
				List<String> descPartList = new ArrayList<String>();
				List<String> pojoSetDescPartList = new ArrayList<String>();

				descPartList.add("(");
				pojoSetDescPartList.add("(");
				pojoSetDescPartList.add("Ljava/lang/Object;");

				for(Field field: fields) {
					String fieldDesc = Type.getType(field.getType()).getDescriptor();
					
					descPartList.add(fieldDesc);
					pojoSetDescPartList.add(fieldDesc);
				}
				descPartList.add(")");
				pojoSetDescPartList.add(")");
				descPartList.add(baseDesc);
				pojoSetDescPartList.add(baseDesc);

				String descriptor = String.join("", descPartList);
				String pojoSetDescriptor = String.join("", pojoSetDescPartList);

				mv = cw.visitMethod(ACC_PUBLIC, "alloc", descriptor, null, null);
				mv.visitCode();
				
				mv.visitVarInsn(ALOAD, 0);
				mv.visitFieldInsn(GETFIELD, name, "supplier", "Ljava/util/function/Supplier;");
				mv.visitMethodInsn(INVOKEINTERFACE, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true);

				for(int i = 0, slot = 1; i < fields.length; i++) {
					Type type = Type.getType(fields[i].getType());
					
					mv.visitVarInsn(type.getOpcode(ILOAD), slot);
					slot += type.getSize();
				}

				mv.visitMethodInsn(INVOKESTATIC, pojoName, "pojoSet", pojoSetDescriptor, false);
				mv.visitInsn(ARETURN);

				mv.visitMaxs(0, 0);
				mv.visitEnd();
			}
			{
				mv = cw.visitMethod(ACC_PUBLIC, "free", "(" + baseDesc + ")V", null, null);

				mv.visitVarInsn(ALOAD, 0);
				mv.visitFieldInsn(GETFIELD, name, "consumer", "Ljava/util/function/Consumer;");
				mv.visitVarInsn(ALOAD, 1);
				if(!name.endsWith("$NoClear"))
					mv.visitMethodInsn(INVOKESTATIC, pojoName, "pojoClear", "(Ljava/lang/Object;)" + baseDesc, false);
				mv.visitMethodInsn(INVOKEINTERFACE, "java/util/function/Consumer", "accept", "(Ljava/lang/Object;)V", true);
				mv.visitInsn(RETURN);
				mv.visitMaxs(0, 0);
				mv.visitEnd();
			}
			cw.visitEnd();

			return cw.toByteArray();
		}

		private byte[] createConstructingSupplierClassBytes (String name, String pojoName) {
			ClassWriter cw = new ClassWriter(0);
			MethodVisitor mv;

			cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, name, null, "java/lang/Object", new String[] { "java/util/function/Supplier" });

			{
				mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
				mv.visitCode();
				mv.visitVarInsn(ALOAD, 0);
				mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
				mv.visitInsn(RETURN);
				mv.visitMaxs(1, 1);
				mv.visitEnd();
			}
			{
				mv = cw.visitMethod(ACC_PUBLIC, "get", "()Ljava/lang/Object;", null, null);
				mv.visitTypeInsn(NEW, pojoName);
				mv.visitInsn(DUP);
				mv.visitMethodInsn(INVOKESPECIAL, pojoName, "<init>", "()V", false);
				mv.visitInsn(ARETURN);
				mv.visitMaxs(2, 1);
				mv.visitEnd();
			}
			cw.visitEnd();

			return cw.toByteArray();

		}

		private byte[] createPOJOClassBytes (String name, Class<?> baseClass) throws SecurityException, ClassNotFoundException {
			Type baseType = Type.getType(baseClass);
			String baseName = baseType.getInternalName();
			Field[] fields = getFields(baseClass);
			
			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			MethodVisitor mv;

			cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, name, null, baseName, null);

			{
				mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
				mv.visitCode();
				mv.visitVarInsn(ALOAD, 0);
				mv.visitMethodInsn(INVOKESPECIAL, baseName, "<init>", "()V", false);
				mv.visitInsn(RETURN);
				mv.visitMaxs(0, 0);
				mv.visitEnd();
			}
			{
				List<String> descPartList = new ArrayList<String>();

				descPartList.add("(");
				descPartList.add("Ljava/lang/Object;");
				for(Field field: fields)
					descPartList.add(Type.getType(field.getType()).getDescriptor());
				descPartList.add(")");
				descPartList.add(baseType.getDescriptor());

				String descriptor = String.join("", descPartList);

				mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "pojoSet", descriptor, null, null);
				mv.visitCode();

				mv.visitVarInsn(ALOAD, 0);
				mv.visitTypeInsn(CHECKCAST, name);

				for(int i = 0, slot = 1; i < fields.length; i++) {
					Field field = fields[i];
					Type type = Type.getType(field.getType());

					mv.visitInsn(DUP);
					mv.visitVarInsn(type.getOpcode(ILOAD), slot);
					mv.visitFieldInsn(PUTFIELD, Type.getType(field.getDeclaringClass()).getInternalName(), field.getName(), type.getDescriptor());
					slot += type.getSize();
				}

				mv.visitInsn(ARETURN);

				mv.visitMaxs(0, 0);
				mv.visitEnd();
			}
			{
				mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "pojoClear", "(Ljava/lang/Object;)" + baseType.getDescriptor(), null, null);
				mv.visitCode();

				mv.visitVarInsn(ALOAD, 0);
				mv.visitTypeInsn(CHECKCAST, name);

				for(int i = 0; i < fields.length; i++) {
					Field field = fields[i];
					Class<?> fieldType = field.getType();

					if(!fieldType.isPrimitive() && !field.isAnnotationPresent(NoClear.class)) {
						mv.visitInsn(DUP);
						mv.visitInsn(ACONST_NULL);
						mv.visitFieldInsn(PUTFIELD, baseName, field.getName(), Type.getType(fieldType).getDescriptor());
					}
				}

				mv.visitInsn(ARETURN);

				mv.visitMaxs(0, 0);
				mv.visitEnd();
			}

			cw.visitEnd();

			return cw.toByteArray();
		}
	}
}
