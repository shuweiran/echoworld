import{S as a}from"./BabylonSimulationView-CdKNwLC_.js";import"./index-M38jcyXX.js";const e="textureAlphaCheckerPixelShader",r=`
precision highp float;uniform sampler2D textureSampler;varying vec2 vUv;void main() {gl_FragColor=vec4(vec3(1.0)-vec3(texture2D(textureSampler,vUv).a),1.0);}
`;a.ShadersStore[e]=r;const h={name:e,shader:r};export{h as TextureAlphaCheckerPixelShader};
