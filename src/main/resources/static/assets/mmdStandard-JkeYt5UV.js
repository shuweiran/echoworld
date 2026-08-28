import{f}from"./BabylonSimulationView-CdKNwLC_.js";import{M as o}from"./mmdModelLoader.default-CpBhY_jL.js";import"./index-M38jcyXX.js";import"./pmxObject-DPJR4f8j.js";function n(t){return t.replace(/[.*+?^${}()|[\]\\]/g,"\\$&")}const a=`
#ifndef SDEFDECLARATION
#define SDEFDECLARATION
#if NUM_BONE_INFLUENCERS>0 && defined(SDEF)
attribute vec3 matricesSdefC;attribute vec3 matricesSdefRW0;attribute vec3 matricesSdefRW1;vec4 rotationMatrixToQuaternion(mat3 matrix) {float trace=matrix[0][0]+matrix[1][1]+matrix[2][2];float s;float sqrtParam;if (trace>0.0) {sqrtParam=trace+1.0;} else if (matrix[0][0]>matrix[1][1] && matrix[0][0]>matrix[2][2]) {sqrtParam=1.0+matrix[0][0]-matrix[1][1]-matrix[2][2];} else if (matrix[1][1]>matrix[2][2]) {sqrtParam=1.0+matrix[1][1]-matrix[0][0]-matrix[2][2];} else {sqrtParam=1.0+matrix[2][2]-matrix[0][0]-matrix[1][1];}
float sqrtValue=sqrt(sqrtParam);if (trace>0.0) {s=0.5/sqrtValue;return vec4(
(matrix[1][2]-matrix[2][1])*s,
(matrix[2][0]-matrix[0][2])*s,
(matrix[0][1]-matrix[1][0])*s,
0.25/s
);} else if (matrix[0][0]>matrix[1][1] && matrix[0][0]>matrix[2][2]) {s=2.0*sqrtValue;return vec4(
0.25*s,
(matrix[0][1]+matrix[1][0])/s,
(matrix[2][0]+matrix[0][2])/s,
(matrix[1][2]-matrix[2][1])/s
);} else if (matrix[1][1]>matrix[2][2]) {s=2.0*sqrtValue;return vec4(
(matrix[0][1]+matrix[1][0])/s,
0.25*s,
(matrix[1][2]+matrix[2][1])/s,
(matrix[2][0]-matrix[0][2])/s
);} else {s=2.0*sqrtValue;return vec4(
(matrix[2][0]+matrix[0][2])/s,
(matrix[1][2]+matrix[2][1])/s,
0.25*s,
(matrix[0][1]-matrix[1][0])/s
);}}
mat3 quaternionToRotationMatrix(vec4 q) {float xx=q.x*q.x;float yy=q.y*q.y;float zz=q.z*q.z;float xy=q.x*q.y;float zw=q.z*q.w;float zx=q.z*q.x;float yw=q.y*q.w;float yz=q.y*q.z;float xw=q.x*q.w;return mat3(
1.0-2.0*(yy+zz),2.0*(xy+zw),2.0*(zx-yw),
2.0*(xy-zw),1.0-2.0*(zz+xx),2.0*(yz+xw),
2.0*(zx+yw),2.0*(yz-xw),1.0-2.0*(yy+xx)
);}
vec4 slerp(vec4 q0,vec4 q1,float t) {float cosTheta=dot(q0,q1);q1=mix(-q1,q1,step(0.0,cosTheta));cosTheta=abs(cosTheta);if (cosTheta>0.999999) {return normalize(mix(q0,q1,t));}
float theta=acos(cosTheta);float sinTheta=sin(theta);float w0=sin((1.0-t)*theta)/sinTheta;float w1=sin(t*theta)/sinTheta;return q0*w0+q1*w1;}
#endif
#endif
`,s=`
#ifndef SDEFVERTEX
#define SDEFVERTEX
#if !defined(BAKED_VERTEX_ANIMATION_TEXTURE) && defined(SDEF)
#if NUM_BONE_INFLUENCERS>0
{float weight0=matricesWeights[0];float weight1=matricesWeights[1];
#ifdef BONETEXTURE
mat4 transformMatrix0=readMatrixFromRawSampler(boneSampler,matricesIndices[0]);mat4 transformMatrix1=readMatrixFromRawSampler(boneSampler,matricesIndices[1]);
#else
mat4 transformMatrix0=mBones[int(matricesIndices[0])];mat4 transformMatrix1=mBones[int(matricesIndices[1])];
#endif
mat3 slerpedRotationMatrix=quaternionToRotationMatrix(slerp(
rotationMatrixToQuaternion(mat3(transformMatrix0)),
rotationMatrixToQuaternion(mat3(transformMatrix1)),
weight1
));mat4 sdefInflunce=mat4(
vec4(1.0,0.0,0.0,0.0),
vec4(0.0,1.0,0.0,0.0),
vec4(0.0,0.0,1.0,0.0),
vec4(-matricesSdefC,1.0)
);mat4 rotationMatrix=mat4(
vec4(slerpedRotationMatrix[0],0.0),
vec4(slerpedRotationMatrix[1],0.0),
vec4(slerpedRotationMatrix[2],0.0),
vec4(0.0,0.0,0.0,1.0)
);sdefInflunce=rotationMatrix*sdefInflunce;vec3 positionOffset =
vec3(transformMatrix0*vec4(matricesSdefRW0,1))*weight0 +
vec3(transformMatrix1*vec4(matricesSdefRW1,1))*weight1;sdefInflunce[3]+=vec4(positionOffset,0.0);float useLinearDeform=step(0.0,-abs(matricesSdefRW0.x));influence=mat4(
mix(sdefInflunce[0],influence[0],useLinearDeform),
mix(sdefInflunce[1],influence[1],useLinearDeform),
mix(sdefInflunce[2],influence[2],useLinearDeform),
mix(sdefInflunce[3],influence[3],useLinearDeform)
);}
#endif
#endif
#endif
`;class c extends o{isCompatible(i){switch(i){case f.GLSL:return!0;default:return!1}}getCustomCode(i){if(i==="vertex"){const e={};return e.CUSTOM_VERTEX_DEFINITIONS=a,e[`!${n("finalWorld=finalWorld*influence;")}`]=`
${s}
finalWorld=(finalWorld*influence);
`,e}if(i==="fragment"){const e={};e.CUSTOM_FRAGMENT_DEFINITIONS=`
#if defined(SPHERE_TEXTURE) && defined(NORMAL)
uniform sampler2D sphereSampler;
#endif
#ifdef TOON_TEXTURE
uniform sampler2D toonSampler;
#endif
`,e[`!${n(`#if defined(REFLECTIONMAP_SPHERICAL) || defined(REFLECTIONMAP_PROJECTION) || defined(REFRACTION) || defined(PREPASS)
uniform mat4 view;
#endif`)}`]=`
#if defined(REFLECTIONMAP_SPHERICAL) || defined(REFLECTIONMAP_PROJECTION) || defined(REFRACTION) || defined(PREPASS)
uniform mat4 view;
#elif defined(NORMAL) && defined(SPHERE_TEXTURE)
uniform mat4 view;
#endif
`,e.CUSTOM_FRAGMENT_MAIN_BEGIN=`
#ifdef TOON_TEXTURE
vec3 toonNdl;
#endif
`,e[`!${n("vec3 diffuseColor=vDiffuseColor.rgb;")}`]=`
#ifdef APPLY_AMBIENT_COLOR_TO_DIFFUSE
vec3 diffuseColor=clamp(vDiffuseColor.rgb+vAmbientColor,0.0,1.0);
#else
vec3 diffuseColor=(vDiffuseColor.rgb);
#endif
`,e[`!${n("float alpha=vDiffuseColor.a;")}`]=`
#ifdef CLAMP_ALPHA
float alpha=clamp(vDiffuseColor.a,0.0,1.0);
#else
float alpha=vDiffuseColor.a;
#endif
`,e[`!${n("baseColor=texture2D(diffuseSampler,vDiffuseUV+uvOffset);")}`]=`
#if defined(DIFFUSE) && defined(TEXTURE_COLOR)
baseColor=texture2D(diffuseSampler,(vDiffuseUV+uvOffset));baseColor.rgb=mix(
vec3(1.0),
baseColor.rgb*textureMultiplicativeColor.rgb,
textureMultiplicativeColor.a
);baseColor.rgb=clamp(
baseColor.rgb+(baseColor.rgb-vec3(1.0))*textureAdditiveColor.a,
0.0,
1.0
)+textureAdditiveColor.rgb;
#else
baseColor=texture2D(diffuseSampler,(vDiffuseUV+uvOffset));
#endif
`,e[`!${n(`struct lightingInfo
{`)}`]=`
struct lightingInfo {
#ifdef TOON_TEXTURE
#ifndef NDOTL
float ndl;
#endif
float isToon;
#endif
`,e[`!${n("result.diffuse=ndl*diffuseColor*attenuation;")}`]=`
#ifdef TOON_TEXTURE
result.diffuse=diffuseColor*attenuation;result.ndl=ndl;result.isToon=1.0;
#elif defined(IGNORE_DIFFUSE_WHEN_TOON_TEXTURE_DISABLED) 
result.diffuse=diffuseColor*attenuation;
#else
result.diffuse=(ndl*diffuseColor*attenuation);
#endif
`,e[`!${n("diffuseBase+=info.diffuse*shadow;")}`]=`
#ifdef TOON_TEXTURE
toonNdl=vec3(clamp(info.ndl*shadow,0.02,0.98));toonNdl.r=texture2D(toonSampler,vec2(0.5,toonNdl.r)).r;toonNdl.g=texture2D(toonSampler,vec2(0.5,toonNdl.g)).g;toonNdl.b=texture2D(toonSampler,vec2(0.5,toonNdl.b)).b;
#ifdef TOON_TEXTURE_COLOR
toonNdl=mix(
vec3(1.0),
toonNdl*toonTextureMultiplicativeColor.rgb,
toonTextureMultiplicativeColor.a
);toonNdl=clamp(
toonNdl+(toonNdl-vec3(1.0))*toonTextureAdditiveColor.a,
0.0,
1.0
)+toonTextureAdditiveColor.rgb;
#endif
diffuseBase+=mix(info.diffuse*shadow,toonNdl*info.diffuse,info.isToon);
#elif defined(IGNORE_DIFFUSE_WHEN_TOON_TEXTURE_DISABLED)
diffuseBase+=info.diffuse;
#else
diffuseBase+=(info.diffuse*shadow);
#endif
`;const r=`
#ifdef EMISSIVEASILLUMINATION
vec3 finalDiffuse=clamp(diffuseBase*diffuseColor+vAmbientColor,0.0,1.0)*baseColor.rgb;
#else
#ifdef LINKEMISSIVEWITHDIFFUSE
vec3 finalDiffuse=clamp((diffuseBase+emissiveColor)*diffuseColor+vAmbientColor,0.0,1.0)*baseColor.rgb;
#else
vec3 finalDiffuse=clamp(diffuseBase*diffuseColor+emissiveColor+vAmbientColor,0.0,1.0)*baseColor.rgb;
#endif
#endif
`;return e[`!${n(r)}`]=`
#ifdef APPLY_AMBIENT_COLOR_TO_DIFFUSE
#ifdef EMISSIVEASILLUMINATION
vec3 finalDiffuse=clamp(diffuseBase*diffuseColor,0.0,1.0)*baseColor.rgb;
#else
#ifdef LINKEMISSIVEWITHDIFFUSE
vec3 finalDiffuse=clamp((diffuseBase+emissiveColor)*diffuseColor,0.0,1.0)*baseColor.rgb;
#else
vec3 finalDiffuse=clamp(diffuseBase*diffuseColor+emissiveColor,0.0,1.0)*baseColor.rgb;
#endif
#endif
#else
${r.replace("diffuseBase","(diffuseBase)")}#endif
`,e.CUSTOM_FRAGMENT_BEFORE_FOG=`
#if defined(NORMAL) && defined(SPHERE_TEXTURE)
vec3 viewSpaceNormal=normalize(mat3(view)*vNormalW);vec2 sphereUV=viewSpaceNormal.xy*0.5+0.5;vec4 sphereReflectionColor=texture2D(sphereSampler,sphereUV);
#ifdef SPHERE_TEXTURE_COLOR
sphereReflectionColor.rgb=mix(
vec3(1.0),
sphereReflectionColor.rgb*sphereTextureMultiplicativeColor.rgb,
sphereTextureMultiplicativeColor.a
);sphereReflectionColor.rgb=clamp(
sphereReflectionColor.rgb+(sphereReflectionColor.rgb-vec3(1.0))*sphereTextureAdditiveColor.a,
0.0,
1.0
)+sphereTextureAdditiveColor.rgb;
#endif
sphereReflectionColor.rgb*=diffuseBase;
#ifdef SPHERE_TEXTURE_BLEND_MODE_MULTIPLY
color*=sphereReflectionColor;
#elif defined(SPHERE_TEXTURE_BLEND_MODE_ADD)
color=vec4(color.rgb+sphereReflectionColor.rgb,color.a);
#endif
#endif
`,e}return null}getUniforms(i){return{...super.getUniforms(i),fragment:`
#if defined(DIFFUSE) && defined(TEXTURE_COLOR)
uniform vec4 textureMultiplicativeColor;uniform vec4 textureAdditiveColor;
#endif
#if defined(SPHERE_TEXTURE) && defined(SPHERE_TEXTURE_COLOR)
uniform vec4 sphereTextureMultiplicativeColor;uniform vec4 sphereTextureAdditiveColor;
#endif
#if defined(TOON_TEXTURE) && defined(TOON_TEXTURE_COLOR)
uniform vec4 toonTextureMultiplicativeColor;uniform vec4 toonTextureAdditiveColor;
#endif
`}}}export{c as MmdPluginMaterial};
