package com.redhat.ceylon.compiler.loader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.redhat.ceylon.compiler.js.MetamodelGenerator;
import com.redhat.ceylon.compiler.typechecker.analyzer.ModuleManager;
import com.redhat.ceylon.compiler.typechecker.model.Declaration;
import com.redhat.ceylon.compiler.typechecker.model.FunctionalParameter;
import com.redhat.ceylon.compiler.typechecker.model.Getter;
import com.redhat.ceylon.compiler.typechecker.model.Interface;
import com.redhat.ceylon.compiler.typechecker.model.IntersectionType;
import com.redhat.ceylon.compiler.typechecker.model.Method;
import com.redhat.ceylon.compiler.typechecker.model.MethodOrValue;
import com.redhat.ceylon.compiler.typechecker.model.Parameter;
import com.redhat.ceylon.compiler.typechecker.model.ParameterList;
import com.redhat.ceylon.compiler.typechecker.model.ProducedType;
import com.redhat.ceylon.compiler.typechecker.model.Scope;
import com.redhat.ceylon.compiler.typechecker.model.TypeDeclaration;
import com.redhat.ceylon.compiler.typechecker.model.TypeParameter;
import com.redhat.ceylon.compiler.typechecker.model.UnionType;
import com.redhat.ceylon.compiler.typechecker.model.Unit;
import com.redhat.ceylon.compiler.typechecker.model.Value;
import com.redhat.ceylon.compiler.typechecker.model.ValueParameter;

public class JsonPackage extends com.redhat.ceylon.compiler.typechecker.model.Package {

    //Ugly hack to have a ref to IdentifiableObject at hand
    private final static Map<String,Object> idobj = new HashMap<String, Object>();
    private final static Map<String,Object> idifc = new HashMap<String, Object>();
    private Map<String,Object> model;
    private final Unit unit = new Unit();
    private final String pkgname;
    private boolean loaded = false;

    static {
        idobj.put(MetamodelGenerator.KEY_NAME, "IdentifiableObject");
        idobj.put(MetamodelGenerator.KEY_PACKAGE, "ceylon.language");
        idobj.put(MetamodelGenerator.KEY_MODULE, "ceylon.language");
        idifc.put(MetamodelGenerator.KEY_NAME, "Identifiable");
        idifc.put(MetamodelGenerator.KEY_PACKAGE, "ceylon.language");
        idifc.put(MetamodelGenerator.KEY_MODULE, "ceylon.language");
    }
    public JsonPackage(String pkgname) {
        this.pkgname = pkgname;
        setName(ModuleManager.splitModuleName(pkgname));
        unit.setPackage(this);
        unit.setFilename("package.ceylon");
        addUnit(unit);
    }
    void setModel(Map<String, Object> metamodel) {
        model = metamodel;
    }

    void loadDeclarations() {
        if (loaded) return;
        loaded = true;
        if (getModule().getLanguageModule() == getModule()) {
            //Mark the language module as immediately available to bypass certain validations
            getModule().setAvailable(true);
            //Ugly ass hack - add Bottom to the model
            Map<String,Object> bottom = new HashMap<String, Object>();
            bottom.put(MetamodelGenerator.KEY_NAME, "Bottom");
            bottom.put(MetamodelGenerator.KEY_METATYPE, MetamodelGenerator.METATYPE_CLASS);
            bottom.put(MetamodelGenerator.KEY_PACKAGE, "ceylon.language");
            loadClass("Bottom", bottom, this);
            System.out.println("marking langmod available - SHOULD HAPPEN ONLY ONCE");
        }
        setShared(model.get("$pkg-shared") != null);
        for (Map.Entry<String,Object> e : model.entrySet()) {
            String k = e.getKey();
            if (!k.startsWith("$pkg-")) {
                @SuppressWarnings("unchecked")
                Map<String,Object> m = (Map<String,Object>)e.getValue();
                String metatype = (String)m.get(MetamodelGenerator.KEY_METATYPE);
                if (metatype == null) {
                    throw new IllegalArgumentException("Missing metatype from entry " + m);
                }
                if (MetamodelGenerator.METATYPE_CLASS.equals(metatype)) {
                    loadClass(e.getKey(), m, this);
                } else if (MetamodelGenerator.METATYPE_INTERFACE.equals(metatype)) {
                    loadInterface(e.getKey(), m, this);
                } else if (metatype.equals(MetamodelGenerator.METATYPE_ATTRIBUTE)
                        || metatype.equals(MetamodelGenerator.METATYPE_GETTER)) {
                    loadAttribute(k, m, this, null);
                } else if (metatype.equals(MetamodelGenerator.METATYPE_METHOD)) {
                    loadMethod(k, m, this, null);
                } else if (metatype.equals(MetamodelGenerator.METATYPE_OBJECT)) {
                    loadObject(k, m, this);
                }
            }
        }
    }

    @Override
    public List<Declaration> getMembers() {
        return super.getMembers();
    }

    @SuppressWarnings("unchecked")
    private com.redhat.ceylon.compiler.typechecker.model.Class loadClass(String name, Map<String, Object> m, Scope parent) {
        com.redhat.ceylon.compiler.typechecker.model.Class cls = new com.redhat.ceylon.compiler.typechecker.model.Class();
        cls.setAbstract(m.containsKey("abstract"));
        setDefaultSharedActualFormal(cls, m);
        cls.setAnonymous(m.containsKey("$anon"));
        cls.setContainer(parent);
        cls.setName(name);
        cls.setUnit(unit);
        unit.addDeclaration(cls);
        //Type parameters are about the first thing we need to load
        final List<TypeParameter> tparms = parseTypeParameters(
                (List<Map<String,Object>>)m.get(MetamodelGenerator.KEY_TYPE_PARAMS), cls);
        if (m.containsKey("super")) {
            cls.setExtendedType(getTypeFromJson((Map<String,Object>)m.get("super"), tparms));
        } else if (!(getModule().getLanguageModule()==getModule() && ("Bottom".equals(name) || "Void".equals(name)))) {
            cls.setExtendedType(getTypeFromJson(idobj, tparms));
        }
        if (tparms != null) {
            cls.setTypeParameters(tparms);
        }

        ParameterList plist = parseParameters((List<Map<String,Object>>)m.get(MetamodelGenerator.KEY_PARAMS),
                cls, tparms);
        plist.setNamedParametersSupported(true);
        cls.setParameterList(plist);
        if (m.containsKey("of")) {
            cls.setCaseTypes(parseTypeList((List<Map<String,Object>>)m.get("of"), tparms));
        }
        if (m.containsKey("satisfies")) {
            cls.setSatisfiedTypes(parseTypeList((List<Map<String,Object>>)m.get("satisfies"), tparms));
        }
        addTypeConstraints((List<Map<String,Object>>)m.get(MetamodelGenerator.KEY_TYPE_CONSTR), tparms);
        addAttributesAndMethods(m, cls, tparms);
        return cls;
    }

    /** Parses the specified attributes and methods from JSON data and adds them to the specified declaration. */
    @SuppressWarnings("unchecked")
    private void addAttributesAndMethods(Map<String,Object> m, Declaration d, List<TypeParameter> tparms) {
        //Attributes
        Map<String, Map<String,Object>> sub = (Map<String,Map<String,Object>>)m.get(MetamodelGenerator.KEY_ATTRIBUTES);
        if (sub != null) {
            for(Map.Entry<String, Map<String,Object>> e : sub.entrySet()) {
                d.getMembers().add(loadAttribute(e.getKey(), e.getValue(), (Scope)d, tparms));
            }
        }
        //Methods
        sub = (Map<String,Map<String,Object>>)m.get(MetamodelGenerator.KEY_METHODS);
        if (sub != null) {
            for(Map.Entry<String, Map<String,Object>> e : sub.entrySet()) {
                d.getMembers().add(loadMethod(e.getKey(), e.getValue(), (Scope)d, tparms));
            }
        }
    }

    /** Parses the list of type constraints to add them to the type parameters. */
    @SuppressWarnings("unchecked")
    private void addTypeConstraints(List<Map<String,Object>> typeConstraints, List<TypeParameter> typeParams) {
        if (typeConstraints == null) return;
        for (Map<String,Object> tc : typeConstraints) {
            for (TypeParameter tp : typeParams) {
                if (tp.getName().equals(tc.get(MetamodelGenerator.KEY_NAME))) {
                    if (tc.containsKey("satisfies")) {
                        tp.setSatisfiedTypes(parseTypeList((List<Map<String,Object>>)tc.get("satisfies"), typeParams));
                    } else if (tc.containsKey("of")) {
                        tp.setCaseTypes(parseTypeList((List<Map<String,Object>>)tc.get("of"), typeParams));
                        System.out.println("TC casetypes para " + tp + ": " + tc.get("of") + " que tiene " + tp.getCaseTypes());
                    }
                }
            }
        }
    }
    /** Creates a list of ProducedType from the references in the maps.
     * @param types A list of maps where each map is a reference to a type or type parameter.
     * @param typeParams The type parameters that can be referenced from the list of maps. */
    private List<ProducedType> parseTypeList(List<Map<String,Object>> types, List<TypeParameter> typeParams) {
        List<ProducedType> ts = new ArrayList<ProducedType>(types.size());
        for (Map<String,Object> st : types) {
            ts.add(getTypeFromJson(st, typeParams));
        }
        return ts;
    }

    /** Creates a list of TypeParameter from a list of maps.
     * @param typeParams The list of maps to create the TypeParameters.
     * @param container The declaration which owns the resulting type parameters. */
    private List<TypeParameter> parseTypeParameters(List<Map<String,Object>> typeParams, Declaration container) {
        if (typeParams == null) return null;
        List<TypeParameter> tparms = new ArrayList<TypeParameter>(typeParams.size());
        for (Map<String,Object> tp : typeParams) {
            TypeParameter tparm = new TypeParameter();
            tparm.setName((String)tp.get(MetamodelGenerator.KEY_NAME));
            String variance = (String)tp.get("variance");
            if ("out".equals(variance)) {
                tparm.setCovariant(true);
            } else if ("in".equals(variance)) {
                tparm.setContravariant(true);
            }
            tparm.setSequenced(tp.containsKey("seq"));
            tparm.setUnit(unit);
            tparm.setDeclaration(container);
            tparms.add(tparm);
        }
        return tparms;
    }

    /** Creates a parameter list from a list of maps where each map represents a parameter.
     * @param The list of maps to create the parameters.
     * @param owner The declaration to assign to each parameter.
     * @param typeParameters The type parameters which can be referenced from the parameters. */
    private ParameterList parseParameters(List<Map<String,Object>> params, Declaration owner, List<TypeParameter> typeParameters) {
        ParameterList plist = new ParameterList();
        if (params != null) {
            for (Map<String,Object> p : params) {
                Parameter param = null;
                String paramtype = (String)p.get("$pt");
                if ("v".equals(paramtype)) {
                    param = new ValueParameter();
                } else if ("f".equals(paramtype)) {
                    param = new FunctionalParameter();
                } else {
                    throw new IllegalArgumentException("Unknown parameter type " + paramtype);
                }
                param.setName((String)p.get(MetamodelGenerator.KEY_NAME));
                param.setUnit(unit);
                param.setDeclaration(owner);
                if (p.get(MetamodelGenerator.KEY_TYPE) instanceof Map) {
                    param.setType(getTypeFromJson((Map<String,Object>)p.get(MetamodelGenerator.KEY_TYPE), typeParameters));
                } else {
                    //parameter type
                    for (TypeParameter tp : typeParameters) {
                        if (tp.getName().equals(p.get(MetamodelGenerator.KEY_TYPE))) {
                            param.setType(tp.getType());
                        }
                    }
                }
                plist.getParameters().add(param);
            }
        }
        return plist;
    }

    private Method loadMethod(String name, Map<String, Object> m, Scope parent, List<TypeParameter> tparams) {
        Method md = new Method();
        md.setName(name);
        md.setContainer(parent);
        setDefaultSharedActualFormal(md, m);
        md.setUnit(unit);
        if (parent == this) {
            //Top-level declarations are directly added to the unit
            unit.addDeclaration(md);
        }
        @SuppressWarnings("unchecked")
        List<TypeParameter> methodtyparms = parseTypeParameters(
                (List<Map<String,Object>>)m.get(MetamodelGenerator.KEY_TYPE_PARAMS), md);
        if (methodtyparms == null) {
            methodtyparms = tparams;
        } else {
            md.setTypeParameters(methodtyparms);
            if (tparams != null) {
                methodtyparms.addAll(tparams);
            }
        }
        md.setType(getTypeFromJson((Map<String,Object>)m.get(MetamodelGenerator.KEY_TYPE), methodtyparms));
        md.addParameterList(parseParameters((List<Map<String,Object>>)m.get(MetamodelGenerator.KEY_PARAMS),
                md, methodtyparms));
        return md;
    }

    private MethodOrValue loadAttribute(String name, Map<String, Object> m, Scope parent,
            List<TypeParameter> typeParameters) {
        String metatype = (String)m.get(MetamodelGenerator.KEY_METATYPE);
        MethodOrValue d = MetamodelGenerator.METATYPE_GETTER.equals(metatype) ? new Getter() : new Value();
        d.setName(name);
        d.setContainer(parent);
        d.setUnit(unit);
        if (parent == this) {
            unit.addDeclaration(d);
        }
        setDefaultSharedActualFormal(d, m);
        if (m.containsKey("var")) {
            ((Value)d).setVariable(true);
        }
        d.setType(getTypeFromJson((Map<String,Object>)m.get(MetamodelGenerator.KEY_TYPE), typeParameters));
        return d;
    }

    private Interface loadInterface(String name, Map<String, Object> m, Scope parent) {
        Interface t = new Interface();
        t.setContainer(parent);
        setDefaultSharedActualFormal(t, m);
        t.setName(name);
        t.setUnit(unit);
        if (!(pkgname.equals("ceylon.language") && name.equals("Identifiable"))) {
            t.setExtendedType(getTypeFromJson(idifc, null));
        }
        unit.addDeclaration(t);
        @SuppressWarnings("unchecked")
        final List<TypeParameter> tparms = parseTypeParameters(
                (List<Map<String,Object>>)m.get(MetamodelGenerator.KEY_TYPE_PARAMS), t);
        if (tparms != null) {
            t.setTypeParameters(tparms);
        }
        if (m.containsKey("of")) {
            t.setCaseTypes(parseTypeList((List<Map<String,Object>>)m.get("of"), tparms));
        }
        if (m.containsKey("satisfies")) {
            t.setSatisfiedTypes(parseTypeList((List<Map<String,Object>>)m.get("satisfies"), tparms));
        }
        addTypeConstraints((List<Map<String,Object>>)m.get(MetamodelGenerator.KEY_TYPE_CONSTR), tparms);
        addAttributesAndMethods(m, t, tparms);
        return t;
    }

    private TypeDeclaration loadObject(String name, Map<String, Object> m, Scope parent) {
        Value obj = new Value();
        obj.setName(name);
        obj.setContainer(parent);
        setDefaultSharedActualFormal(obj, m);
        unit.addDeclaration(obj);
        if (m.containsKey("super")) {
            ProducedType father = getTypeFromJson((Map<String,Object>)m.get("super"), null);
            obj.setType(father);
        }
        return obj.getTypeDeclaration();
    }

    private ProducedType getTypeFromJson(Map<String, Object> m, List<TypeParameter> typeParams) {
        if (m.containsKey("comp")) {
            @SuppressWarnings("unchecked")
            List<Map<String,Object>> tmaps = (List<Map<String,Object>>)m.get(MetamodelGenerator.KEY_TYPES);
            ArrayList<ProducedType> types = new ArrayList<ProducedType>(tmaps.size());
            for (Map<String, Object> tmap : tmaps) {
                types.add(getTypeFromJson(tmap, typeParams));
            }
            if ("u".equals(m.get("comp"))) {
                UnionType ut = new UnionType(unit);
                ut.setCaseTypes(types);
                return ut.getType();
            } else {
                IntersectionType it = new IntersectionType(unit);
                it.setCaseTypes(types);
                return it.getType();
            }
        } else {
            String tname = (String)m.get(MetamodelGenerator.KEY_NAME);
            String pname = (String)m.get(MetamodelGenerator.KEY_PACKAGE);
            String mname = (String)m.get(MetamodelGenerator.KEY_MODULE);
            if (pname == null) {
                //It's a ref to a type parameter
                for (TypeParameter typeParam : typeParams) {
                    if (typeParam.getName().equals(tname)) {
                        return typeParam.getType();
                    }
                }
            }
            com.redhat.ceylon.compiler.typechecker.model.Package rp;
            if (mname == null) {
                //local type
                rp = getModule().getDirectPackage(pname);
            } else if ("ceylon.language".equals(mname)) {
                rp = getModule().getLanguageModule().getRootPackage();
            } else {
                rp = getModule().getPackage(pname);
            }
            if (rp == null) {
                System.out.println("MAMES! null para " + m);
            }
            for (Declaration d : rp.getMembers()) {
                if (d instanceof TypeDeclaration && tname.equals(d.getName())) {
                    return ((TypeDeclaration)d).getType();
                }
            }
            if (rp == this) {
                return ((TypeDeclaration)load(tname)).getType();
            }
        }
        System.out.println("couldn't find type " + m.get(MetamodelGenerator.KEY_NAME) + " for " + m.get(MetamodelGenerator.KEY_MODULE));
        return null;
    }

    Declaration load(String name) {
        @SuppressWarnings("unchecked")
        Map<String,Object> map = (Map<String,Object>)model.get(name);
        if (map == null) {
            System.out.println("NO HAY " + name + ": " + model.keySet());
            throw new IllegalStateException("NULL queriendo cargar " + name);
        }
        String metatype = (String)map.get(MetamodelGenerator.KEY_METATYPE);
        if (metatype == null) {
            throw new IllegalArgumentException("Missing metatype from entry " + map);
        }
        if (metatype.equals(MetamodelGenerator.METATYPE_ATTRIBUTE)
                || metatype.equals(MetamodelGenerator.METATYPE_GETTER)) {
            return loadAttribute(name, map, this, null);
        } else if (metatype.equals(MetamodelGenerator.METATYPE_CLASS)) {
            return loadClass(name, map, this);
        } else if (metatype.equals(MetamodelGenerator.METATYPE_INTERFACE)) {
            return loadInterface(name, map, this);
        } else if (metatype.equals(MetamodelGenerator.METATYPE_METHOD)) {
            return loadMethod(name, map, this, null);
        } else if (metatype.equals(MetamodelGenerator.METATYPE_OBJECT)) {
            return loadObject(name, map, this);
        }
        System.out.println("WTF is this shit " + map);
        return null;
    }

    private void setDefaultSharedActualFormal(Declaration d, Map<String,Object> m) {
        d.setFormal(m.containsKey(MetamodelGenerator.ANN_FORMAL));
        d.setActual(m.containsKey(MetamodelGenerator.ANN_ACTUAL));
        d.setDefault(m.containsKey(MetamodelGenerator.ANN_DEFAULT));
        d.setShared(m.containsKey(MetamodelGenerator.ANN_SHARED));
    }

}