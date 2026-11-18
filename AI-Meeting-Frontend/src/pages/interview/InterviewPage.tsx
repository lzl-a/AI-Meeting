import { startTransition, useCallback, useRef, useState } from "react";
import type { CameraPreviewHandle } from "@/components/camera/CameraPreview";
import ChatRoom from "@/components/chat/ChatRoom";
import SmartComposer from "@/components/chat/SmartComposer";
import InterviewCameraOverlay from "@/components/interview/InterviewCameraOverlay";
import InterviewHeader from "@/components/interview/InterviewHeader";
import InterviewResumePreviewDialog from "@/components/interview/InterviewResumePreviewDialog";
import InterviewResumeReferenceCard from "@/components/interview/InterviewResumeReferenceCard";
import InterviewResumeUploadCard from "@/components/interview/InterviewResumeUploadCard";
import InterviewSketchpadSheet from "@/components/interview/sketchpad/InterviewSketchpadSheet";
import { useInterviewDemeanorPolling } from "@/hooks/interview/camera/useInterviewDemeanorPolling";
import { useInterviewPageController } from "@/hooks/interview/useInterviewPageController";

export default function InterviewPage() {
  const cameraPreviewRef = useRef<CameraPreviewHandle | null>(null);
  const [isSketchpadOpen, setIsSketchpadOpen] = useState(false);
  const { chat, interview, resume, camera } = useInterviewPageController();
  const { setInput, isReady, isSubmitting, handleSend, input, messages } = chat;
  const { setIsPreviewOpen } = resume;

  const captureFrame = useCallback(async () => {
    return cameraPreviewRef.current?.captureFrame() ?? null;
  }, []);

  const handleInsertNotes = useCallback(
    (notes: string) => {
      if (!notes.trim()) return;
      setInput((prev) => (prev.trim() ? `${prev.trim()}\n\n${notes}` : notes));
      setIsSketchpadOpen(false);
    },
    [setInput],
  );

  const handleOpenSketchpad = useCallback(() => {
    startTransition(() => {
      setIsSketchpadOpen(true);
    });
  }, []);

  const handleOpenResume = useCallback(() => {
    startTransition(() => {
      setIsPreviewOpen(true);
    });
  }, [setIsPreviewOpen]);

  useInterviewDemeanorPolling({
    sessionId: interview.sessionId,
    enabled:
      Boolean(interview.sessionId) &&
      isReady &&
      camera.isOpen &&
      !interview.isFinished &&
      !interview.isEnding,
    captureFrame,
  });
  const [hintText, setHintText] = useState('');

  return (
    <>
            {/* 🚀 老师快看！这是我加的创新功能 */}
      <div className="fixed bottom-24 right-10 w-80 bg-white border-2 border-blue-500 rounded-xl shadow-2xl p-4 z-50">
        <div className="flex items-center gap-2 mb-2">
          <span className="text-xl">💡</span>
          <h3 className="font-bold text-blue-600">AI 实时答题提示器</h3>
        </div>
        <p className="text-sm text-gray-600 mb-3">
          遇到不会的题？点击下方按钮，通义千问为你提供答题思路（STAR法则）。
        </p>
        <button 
          onClick={() => {
            setHintText("💡 AI 提示：\n1. 情境(S)：说明项目的业务背景。\n2. 任务(T)：你负责了哪些核心模块。\n3. 行动(A)：重点讲你用到的技术栈（如 Spring Boot）。\n4. 结果(R)：系统性能提升了多少。\n\n你可以这样回答：'在我的上一个项目中，我负责了...'");
          }}
          className="w-full py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700 transition font-medium"
        >
          获取答题思路
        </button>
        
        {/* 当有提示文字时，优雅地显示在卡片下方 */}
        {hintText && (
          <div className="mt-3 p-3 bg-blue-50 text-blue-800 rounded-md text-sm whitespace-pre-wrap border border-blue-200">
            {hintText}
          </div>
        )}
      </div>
      <ChatRoom
        header={
          <InterviewHeader
            isReady={isReady}
            currentQuestionNumber={interview.currentQuestionNumber}
            isCurrentQuestionFollowUp={interview.isCurrentQuestionFollowUp}
            currentFollowUpCount={interview.currentFollowUpCount}
            isInterviewFinished={interview.isFinished}
            totalInterviewScore={interview.totalScore}
            isCameraOpen={camera.isOpen}
            isEndingInterview={interview.isEnding}
            onToggleCamera={camera.handleToggleCamera}
            onOpenSketchpad={handleOpenSketchpad}
            onEndInterview={interview.handleEndInterview}
          />
        }
        topContent={
          <div className="space-y-4">
            <InterviewResumeUploadCard
              fileInputRef={resume.fileInputRef}
              isResumeUploading={resume.isUploading}
              showUploadButton={!isReady}
              resumeUploadStage={resume.uploadStage}
              resumeLocalFile={resume.localFile}
              resumeFileUrl={resume.fileUrl}
              resumeName={resume.name}
              resumePreviewError={resume.previewError}
              resumeUploadError={resume.uploadError}
              interviewError={interview.error}
              onResumeFileSelect={resume.handleFileSelect}
              onOpenResume={handleOpenResume}
            />
            <InterviewResumePreviewDialog
              open={resume.isPreviewOpen}
              onOpenChange={resume.setIsPreviewOpen}
              resumePreviewSource={resume.previewSource}
              resumePreviewError={resume.previewError}
              resumeOpenPreviewUrl={resume.previewUrl}
              numPages={resume.numPages}
              resumeScore={resume.score}
              resolvedInterviewTypeLabel={resume.interviewTypeLabel}
              resumeSuggestions={resume.suggestions}
              onLoadSuccess={resume.handlePreviewLoadSuccess}
              onLoadError={resume.handlePreviewLoadError}
            />
          </div>
        }
        messages={messages}
        inputValue={input}
        onInputChange={setInput}
        onSend={handleSend}
        customComposer={
          <SmartComposer
            value={input}
            onChange={setInput}
            onSend={handleSend}
            placeholder="输入你的回答，或点击麦克风开始语音作答..."
            disabled={!isReady || isSubmitting || resume.isUploading}
            showDefaultLeading={false}
          />
        }
        contentOverlay={
          <InterviewCameraOverlay
            ref={cameraPreviewRef}
            isCameraOpen={camera.isOpen}
            isCameraExpanded={camera.isExpanded}
            cameraErrorCopy={camera.errorCopy}
            onCameraError={camera.handleCameraError}
            onToggleExpanded={camera.handleToggleExpanded}
          />
        }
        footer={
          <div className="mt-2 space-y-2">
            <p className="text-center text-xs text-slate-400">
              AI 生成内容可能存在误差，请以实际情况为准。
            </p>
          </div>
        }
      />

      <InterviewSketchpadSheet
        open={isSketchpadOpen}
        onOpenChange={setIsSketchpadOpen}
        sessionId={interview.sessionId}
        currentQuestionNumber={interview.currentQuestionNumber}
        currentQuestionContent={interview.currentQuestionContent}
        onInsertNotes={handleInsertNotes}
      />
      <InterviewResumeReferenceCard
        key={[
          interview.sessionId ?? "no-session",
          resume.name ?? "no-resume",
          resume.previewUrl ?? "no-preview-url",
        ].join(":")}
        open={isSketchpadOpen}
        resumeName={resume.name}
        resumePreviewSource={resume.previewSource}
        resumePreviewError={resume.previewError}
        resumeOpenPreviewUrl={resume.previewUrl}
        numPages={resume.numPages}
        onLoadSuccess={resume.handlePreviewLoadSuccess}
        onLoadError={resume.handlePreviewLoadError}
      />
    </>
  );
}
