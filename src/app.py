"""
Chat Profile Miner - Streamlit UI
本地演示界面，上传聊天记录 → 查看偏好画像 → 礼物推荐。
"""
import streamlit as st
import json
from datetime import datetime
from pathlib import Path

from pipeline import ProfilePipeline, save_profile, load_profile, generate_report
from schemas import ProfileSnapshot


def main():
    st.set_page_config(
        page_title="Chat Profile Miner",
        page_icon="💝",
        layout="wide"
    )

    st.title("💝 Chat Profile Miner")
    st.caption("从聊天记录中挖掘对方的喜好、厌恶和礼物信号")

    # 侧边栏配置
    with st.sidebar:
        st.header("⚙️ 配置")
        target_name = st.text_input("分析对象名字", value="她")
        self_name = st.text_input("你的名字", value="我")
        use_llm = st.checkbox("使用 LLM 分析隐含偏好", value=True)

        if use_llm:
            llm_api_base = st.text_input("API Base", value="https://api.deepseek.com")
            llm_api_key = st.text_input("API Key", type="password")
            llm_model = st.text_input("模型", value="deepseek-v4-flash")
        else:
            llm_api_base = ""
            llm_api_key = ""
            llm_model = ""

        st.divider()
        st.header("💰 预算范围")
        budget_min = st.number_input("最低 (元)", value=0, step=100)
        budget_max = st.number_input("最高 (元)", value=1000, step=100)

    # 主界面
    tab1, tab2, tab3 = st.tabs(["📤 上传分析", "📊 画像报告", "🎁 礼物推荐"])

    with tab1:
        st.header("上传聊天记录")

        col1, col2 = st.columns(2)

        with col1:
            st.subheader("📁 上传文件")
            uploaded_file = st.file_uploader(
                "支持 txt / json / html",
                type=["txt", "json", "html"]
            )
            platform = st.selectbox(
                "平台",
                ["auto", "wechat", "douyin", "telegram", "plain"]
            )

        with col2:
            st.subheader("📋 粘贴文本")
            paste_text = st.text_area(
                "直接粘贴聊天记录",
                height=200,
                placeholder="我: 这个好看吗\n她: 好好看！但是有点贵\n我: 买呗\n她: 算了舍不得"
            )

        if st.button("🚀 开始分析", type="primary"):
            if uploaded_file:
                # 保存上传文件到临时目录
                temp_path = Path(f"/tmp/chat_upload_{datetime.now().strftime('%H%M%S')}.txt")
                temp_path.write_bytes(uploaded_file.read())

                with st.spinner("正在分析..."):
                    pipeline = ProfilePipeline(
                        target_name=target_name,
                        self_name=self_name,
                        use_llm=use_llm,
                        llm_api_base=llm_api_base,
                        llm_api_key=llm_api_key,
                        llm_model=llm_model,
                    )
                    profile = pipeline.run_from_file(str(temp_path), platform)

                st.session_state["profile"] = profile
                st.success(f"✅ 分析完成！共处理 {profile.total_messages} 条消息")

            elif paste_text:
                with st.spinner("正在分析..."):
                    pipeline = ProfilePipeline(
                        target_name=target_name,
                        self_name=self_name,
                        use_llm=use_llm,
                        llm_api_base=llm_api_base,
                        llm_api_key=llm_api_key,
                        llm_model=llm_model,
                    )
                    profile = pipeline.run_from_text(paste_text)

                st.session_state["profile"] = profile
                st.success(f"✅ 分析完成！共处理 {profile.total_messages} 条消息")

            else:
                st.warning("请上传文件或粘贴聊天记录")

    with tab2:
        profile = st.session_state.get("profile")
        if profile is None:
            st.info("请先在「上传分析」标签页上传聊天记录")
        else:
            pipeline = ProfilePipeline(target_name=target_name)
            report = pipeline.generate_report(profile)
            st.markdown(report)

            # 导出
            col1, col2 = st.columns(2)
            with col1:
                if st.button("💾 保存画像 JSON"):
                    save_profile(profile, "profile_snapshot.json")
                    st.success("已保存到 profile_snapshot.json")

            with col2:
                st.download_button(
                    "📥 下载报告",
                    data=report,
                    file_name=f"{target_name}_profile_{datetime.now().strftime('%Y%m%d')}.md",
                    mime="text/markdown"
                )

    with tab3:
        profile = st.session_state.get("profile")
        if profile is None:
            st.info("请先在「上传分析」标签页上传聊天记录")
        else:
            st.header(f"🎁 给 {target_name} 的礼物推荐")

            from gift_ranker import GiftRanker
            ranker = GiftRanker()
            gifts = ranker.rank_gifts(
                profile.preferences,
                profile.gift_signals,
                profile.avoid,
                budget_min=budget_min,
                budget_max=budget_max
            )

            if not gifts:
                st.warning("暂无足够证据生成推荐，请上传更多聊天记录")
            else:
                for g in gifts[:10]:
                    with st.container():
                        col1, col2, col3 = st.columns([1, 3, 1])

                        with col1:
                            st.metric("排名", f"#{g.rank}")

                        with col2:
                            st.subheader(f"{g.item}")
                            st.caption(f"品类：{g.category} | 价格：{g.price_range}")
                            st.write(f"**理由：** {g.reasoning}")
                            if g.evidence_summary:
                                st.write("**证据：**")
                                for ev in g.evidence_summary[:3]:
                                    st.write(f"  - {ev}")

                        with col3:
                            conf_color = "green" if g.confidence > 0.7 else "orange" if g.confidence > 0.5 else "red"
                            st.metric("匹配度", f"{g.confidence:.0%}")
                            risk_emoji = {"low": "🟢", "medium": "🟡", "high": "🔴"}
                            st.write(f"风险：{risk_emoji.get(g.risk_level, '⚪')} {g.risk_level}")

                        if g.risks:
                            for risk in g.risks:
                                st.warning(f"⚠️ {risk}")

                        st.divider()


if __name__ == "__main__":
    main()
