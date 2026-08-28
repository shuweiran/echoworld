import{f as t}from"./BabylonSimulationView-CdKNwLC_.js";import{M as a}from"./mmdModelLoader.default-CpBhY_jL.js";import"./index-M38jcyXX.js";import"./pmxObject-DPJR4f8j.js";function n(f){return f.replace(/[.*+?^${}()|[\]\\]/g,"\\$&")}const o=`
#ifndef SDEFDECLARATION
#define SDEFDECLARATION
#if NUM_BONE_INFLUENCERS>0 && defined(SDEF)
attribute matricesSdefC: vec3f;attribute matricesSdefRW0: vec3f;attribute matricesSdefRW1: vec3f;fn rotationMatrixToQuaternion(matrix: mat3x3f)->vec4f {let trace: f32=matrix[0][0]+matrix[1][1]+matrix[2][2];var s: f32;var sqrtParam: f32;if (trace>0.0) {sqrtParam=trace+1.0;} else if (matrix[0][0]>matrix[1][1] && matrix[0][0]>matrix[2][2]) {sqrtParam=1.0+matrix[0][0]-matrix[1][1]-matrix[2][2];} else if (matrix[1][1]>matrix[2][2]) {sqrtParam=1.0+matrix[1][1]-matrix[0][0]-matrix[2][2];} else {sqrtParam=1.0+matrix[2][2]-matrix[0][0]-matrix[1][1];}
let sqrtValue: f32=sqrt(sqrtParam);if (trace>0.0) {s=0.5/sqrtValue;return vec4f(
(matrix[1][2]-matrix[2][1])*s,
(matrix[2][0]-matrix[0][2])*s,
(matrix[0][1]-matrix[1][0])*s,
0.25/s
);} else if (matrix[0][0]>matrix[1][1] && matrix[0][0]>matrix[2][2]) {s=2.0*sqrtValue;return vec4f(
0.25*s,
(matrix[0][1]+matrix[1][0])/s,
(matrix[2][0]+matrix[0][2])/s,
(matrix[1][2]-matrix[2][1])/s
);} else if (matrix[1][1]>matrix[2][2]) {s=2.0*sqrtValue;return vec4f(
(matrix[0][1]+matrix[1][0])/s,
0.25*s,
(matrix[1][2]+matrix[2][1])/s,
(matrix[2][0]-matrix[0][2])/s
);} else {s=2.0*sqrtValue;return vec4f(
(matrix[2][0]+matrix[0][2])/s,
(matrix[1][2]+matrix[2][1])/s,
0.25*s,
(matrix[0][1]-matrix[1][0])/s
);}}
fn quaternionToRotationMatrix(q: vec4f)->mat3x3f {let xx: f32=q.x*q.x;let yy: f32=q.y*q.y;let zz: f32=q.z*q.z;let xy: f32=q.x*q.y;let zw: f32=q.z*q.w;let zx: f32=q.z*q.x;let yw: f32=q.y*q.w;let yz: f32=q.y*q.z;let xw: f32=q.x*q.w;return mat3x3f(
1.0-2.0*(yy+zz),2.0*(xy+zw),2.0*(zx-yw),
2.0*(xy-zw),1.0-2.0*(zz+xx),2.0*(yz+xw),
2.0*(zx+yw),2.0*(yz-xw),1.0-2.0*(yy+xx)
);}
fn slerp(q0: vec4f,_q1: vec4f,t: f32)->vec4f {var q1: vec4f=_q1;var cosTheta: f32=dot(q0,q1);q1=mix(-q1,q1,step(0.0,cosTheta));cosTheta=abs(cosTheta);if (cosTheta>0.999999) {return normalize(mix(q0,q1,t));}
var theta: f32=acos(cosTheta);var sinTheta: f32=sin(theta);var w0: f32=sin((1.0-t)*theta)/sinTheta;var w1: f32=sin(t*theta)/sinTheta;return q0*w0+q1*w1;}
#endif
#endif
`,s=`
#ifndef SDEFVERTEX
#define SDEFVERTEX
#if !defined(BAKED_VERTEX_ANIMATION_TEXTURE) && defined(SDEF)
#if NUM_BONE_INFLUENCERS>0
{let weight0: f32=vertexInputs.matricesWeights[0];let weight1: f32=vertexInputs.matricesWeights[1];
#ifdef BONETEXTURE
let transformMatrix0: mat4x4f=readMatrixFromRawSampler(boneSampler,vertexInputs.matricesIndices[0]);let transformMatrix1: mat4x4f=readMatrixFromRawSampler(boneSampler,vertexInputs.matricesIndices[1]);
#else
let transformMatrix0: mat4x4f=uniforms.mBones[int(vertexInputs.matricesIndices[0])];let transformMatrix1: mat4x4f=uniforms.mBones[int(vertexInputs.matricesIndices[1])];
#endif
let slerpedRotationMatrix: mat3x3f=quaternionToRotationMatrix(slerp(
rotationMatrixToQuaternion(mat3x3f(transformMatrix0[0].xyz,transformMatrix0[1].xyz,transformMatrix0[2].xyz)),
rotationMatrixToQuaternion(mat3x3f(transformMatrix1[0].xyz,transformMatrix1[1].xyz,transformMatrix1[2].xyz)),
weight1
));var sdefInflunce: mat4x4f=mat4x4f(
vec4f(1.0,0.0,0.0,0.0),
vec4f(0.0,1.0,0.0,0.0),
vec4f(0.0,0.0,1.0,0.0),
vec4f(-vertexInputs.matricesSdefC,1.0)
);let rotationMatrix: mat4x4f=mat4x4f(
vec4f(slerpedRotationMatrix[0],0.0),
vec4f(slerpedRotationMatrix[1],0.0),
vec4f(slerpedRotationMatrix[2],0.0),
vec4f(0.0,0.0,0.0,1.0)
);sdefInflunce=rotationMatrix*sdefInflunce;let positionOffset: vec3f =
(transformMatrix0*vec4f(vertexInputs.matricesSdefRW0,1)).xyz*weight0 +
(transformMatrix1*vec4f(vertexInputs.matricesSdefRW1,1)).xyz*weight1;sdefInflunce[3]+=vec4f(positionOffset,0.0);let useLinearDeform: f32=step(0.0,-abs(vertexInputs.matricesSdefRW0.x));influence=mat4x4f(
mix(sdefInflunce[0],influence[0],useLinearDeform),
mix(sdefInflunce[1],influence[1],useLinearDeform),
mix(sdefInflunce[2],influence[2],useLinearDeform),
mix(sdefInflunce[3],influence[3],useLinearDeform)
);}
#endif
#endif
#endif
`;class c extends a{isCompatible(r){switch(r){case t.WGSL:return!0;default:return!1}}getCustomCode(r){if(r==="vertex"){const e={};return e.CUSTOM_VERTEX_DEFINITIONS=o,e[`!${n("finalWorld=finalWorld*influence;")}`]=`
${s}
finalWorld=(finalWorld*influence);
`,e}if(r==="fragment"){const e={};e.CUSTOM_FRAGMENT_DEFINITIONS=`
#if defined(SPHERE_TEXTURE) && defined(NORMAL)
var sphereSamplerSampler: sampler;var sphereSampler: texture_2d<f32>;
#endif
#ifdef TOON_TEXTURE
var toonSamplerSampler: sampler;var toonSampler: texture_2d<f32>;
#endif
`,e.CUSTOM_FRAGMENT_MAIN_BEGIN=`
#ifdef TOON_TEXTURE
var toonNdl: vec3f;
#endif
`,e[`!${n("var diffuseColor: vec3f=uniforms.vDiffuseColor.rgb;")}`]=`
#ifdef APPLY_AMBIENT_COLOR_TO_DIFFUSE
var diffuseColor: vec3f=clamp(uniforms.vDiffuseColor.rgb+uniforms.vAmbientColor,vec3f(0.0),vec3f(1.0));
#else
var diffuseColor: vec3f=(uniforms.vDiffuseColor.rgb);
#endif
`,e[`!${n("var alpha: f32=uniforms.vDiffuseColor.a;")}`]=`
#ifdef CLAMP_ALPHA
var alpha: f32=clamp(uniforms.vDiffuseColor.a,0.0,1.0);
#else
var alpha: f32=uniforms.vDiffuseColor.a;
#endif
`,e[`!${n("baseColor=textureSample(diffuseSampler,diffuseSamplerSampler,fragmentInputs.vDiffuseUV+uvOffset);")}`]=`
#if defined(DIFFUSE) && defined(TEXTURE_COLOR)
baseColor=textureSample(diffuseSampler,diffuseSamplerSampler,(fragmentInputs.vDiffuseUV+uvOffset));baseColor=vec4f(
mix(
vec3f(1.0),
baseColor.rgb*uniforms.textureMultiplicativeColor.rgb,
uniforms.textureMultiplicativeColor.a
),
baseColor.a
);baseColor=vec4f(
clamp(
baseColor.rgb+(baseColor.rgb-vec3f(1.0))*uniforms.textureAdditiveColor.a,
vec3f(0.0),
vec3f(1.0)
)+uniforms.textureAdditiveColor.rgb,
baseColor.a
);
#else
baseColor=textureSample(diffuseSampler,diffuseSamplerSampler,(fragmentInputs.vDiffuseUV+uvOffset));
#endif
`,e[`!${n(`struct lightingInfo
{`)}`]=`
struct lightingInfo {
#ifdef TOON_TEXTURE
#ifndef NDOTL
ndl: f32,
#endif
isToon: f32,
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
toonNdl=vec3f(clamp(info.ndl*shadow,0.02,0.98));toonNdl.r=textureSample(toonSampler,toonSamplerSampler,vec2f(0.5,toonNdl.r)).r;toonNdl.g=textureSample(toonSampler,toonSamplerSampler,vec2f(0.5,toonNdl.g)).g;toonNdl.b=textureSample(toonSampler,toonSamplerSampler,vec2f(0.5,toonNdl.b)).b;
#ifdef TOON_TEXTURE_COLOR
toonNdl=mix(
vec3f(1.0),
toonNdl*uniforms.toonTextureMultiplicativeColor.rgb,
uniforms.toonTextureMultiplicativeColor.a
);toonNdl=clamp(
toonNdl+(toonNdl-vec3f(1.0))*uniforms.toonTextureAdditiveColor.a,
vec3f(0.0),
vec3f(1.0)
)+uniforms.toonTextureAdditiveColor.rgb;
#endif
diffuseBase+=mix(info.diffuse*shadow,toonNdl*info.diffuse,info.isToon);
#elif defined(IGNORE_DIFFUSE_WHEN_TOON_TEXTURE_DISABLED)
diffuseBase+=info.diffuse;
#else
diffuseBase+=(info.diffuse*shadow);
#endif
`;const i=`
#ifdef EMISSIVEASILLUMINATION
var finalDiffuse: vec3f=clamp(diffuseBase*diffuseColor+uniforms.vAmbientColor,vec3f(0.0),vec3f(1.0))*baseColor.rgb;
#else
#ifdef LINKEMISSIVEWITHDIFFUSE
var finalDiffuse: vec3f=clamp((diffuseBase+emissiveColor)*diffuseColor+uniforms.vAmbientColor,vec3f(0.0),vec3f(1.0))*baseColor.rgb;
#else
var finalDiffuse: vec3f=clamp(diffuseBase*diffuseColor+emissiveColor+uniforms.vAmbientColor,vec3f(0.0),vec3f(1.0))*baseColor.rgb;
#endif
#endif
`;return e[`!${n(i)}`]=`
#ifdef APPLY_AMBIENT_COLOR_TO_DIFFUSE
#ifdef EMISSIVEASILLUMINATION
var finalDiffuse: vec3f=clamp(diffuseBase*diffuseColor,vec3f(0.0),vec3f(1.0))*baseColor.rgb;
#else
#ifdef LINKEMISSIVEWITHDIFFUSE
var finalDiffuse: vec3f=clamp((diffuseBase+emissiveColor)*diffuseColor,vec3f(0.0),vec3f(1.0))*baseColor.rgb;
#else
var finalDiffuse: vec3f=clamp(diffuseBase*diffuseColor+emissiveColor,vec3f(0.0),vec3f(1.0))*baseColor.rgb;
#endif
#endif
#else
${i.replace("diffuseBase","(diffuseBase)")}#endif
`,e.CUSTOM_FRAGMENT_BEFORE_FOG=`
#if defined(NORMAL) && defined(SPHERE_TEXTURE)
var viewSpaceNormal: vec3f=normalize(mat3x3f(scene.view[0].xyz,scene.view[1].xyz,scene.view[2].xyz)*fragmentInputs.vNormalW);var sphereUV: vec2f=viewSpaceNormal.xy*0.5+0.5;var sphereReflectionColor: vec4f=textureSample(sphereSampler,sphereSamplerSampler,sphereUV);
#ifdef SPHERE_TEXTURE_COLOR
sphereReflectionColor=vec4f(
mix(
vec3f(1.0),
sphereReflectionColor.rgb*uniforms.sphereTextureMultiplicativeColor.rgb,
uniforms.sphereTextureMultiplicativeColor.a
),
sphereReflectionColor.a
);sphereReflectionColor=vec4f(
clamp(
sphereReflectionColor.rgb+(sphereReflectionColor.rgb-vec3f(1.0))*uniforms.sphereTextureAdditiveColor.a,
vec3f(0.0),
vec3f(1.0)
)+uniforms.sphereTextureAdditiveColor.rgb,
sphereReflectionColor.a
);
#endif
sphereReflectionColor=vec4f(sphereReflectionColor.rgb*diffuseBase,sphereReflectionColor.a);
#ifdef SPHERE_TEXTURE_BLEND_MODE_MULTIPLY
color*=sphereReflectionColor;
#elif defined(SPHERE_TEXTURE_BLEND_MODE_ADD)
color=vec4f(color.rgb+sphereReflectionColor.rgb,color.a);
#endif
#endif
`,e}return null}}export{c as MmdPluginMaterial};
